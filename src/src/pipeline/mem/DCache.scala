package pipeline.mem

import axi.BetterAxiMaster
import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import chisel3.util.random.LFSR
import pipeline.mem.bundles.{DCacheAccessPort, StatusTagBundle}
import pipeline.mem.enums.{DCacheState => State, ReadWriteSel}
import spec._

class DCache(
  isDebug:           Boolean   = false,
  debugAddrSeq:      Seq[UInt] = Seq(),
  debugDataLineSeq:  Seq[UInt] = Seq(),
  debugStatusTagSeq: Seq[UInt] = Seq(),
  debugSetNumSeq:    Seq[Int]  = Seq())
    extends Module {
  val io = IO(new Bundle {
    val accessPort    = new DCacheAccessPort
    val axiMasterPort = new AxiMasterPort
  })

  // TODO: Remove DontCare
  io <> DontCare

  // Read cache hit diagram:
  // clock: ^_____________________________^___________________
  //        isReady = T                   isReady = T
  //        isValid = T                   read.isValid = T
  //        rw = Read                     read.data = *data*
  //        (stored as previous request)
  //
  // state: ready                         ready
  // note:
  //   - Read from write info if current cycle has write cache operation

  // Read cache miss diagram:
  // clock: ^_____________________________^_________________________________^...^___________________^_____________________________________________________^...^______________
  //        isReady = T                   isReady = F                           (get axi data)      read.data = *data*
  //        isValid = T                                                         (fill cache)        read.isValid = T
  //        rw = Read
  //        (stored as previous request)  (axi read request from previous)
  //                                      [if swap out dirty]:                                      [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        (axi write request)                                       isReady = F                                               isReady = T
  //                                                                                                [else]:
  //                                                                                                  isReady = T
  //
  // state: ready                         [if swap out dirty]:                                      [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        refillForReadAndWb                                        onlyWb                                                    ready
  //                                      [else]:                                                   [else]:
  //                                        refillForRead                                             ready
  // note:
  //   - Set reg `isRequestSent` to F before entering state for cache miss,
  //     then set reg `isRequestSent` to T after entering
  //   - Read from write info if current cycle has write cache operation
  //   - `refillForReadAndWb` is merged to `refillForRead` with `isNeedWbReg`

  // Write cache hit diagram:
  // clock: ^_____________________________^_______________^____________
  //        isReady = T                   isReady = F     isReady = T
  //        isValid = T                   isComplete = T
  //        rw = Write                    (write cache)
  //        (stored as previous request)
  //
  // state: ready                         write           ready

  // Write cache miss diagram:
  // clock: ^_____________________________^_________________________________^...^________________________^_____________________________________________________^...^______________
  //        isReady = T                   isReady = F                           (get axi data)
  //        isValid = T                                                         (fill cache with write)
  //        rw = Write
  //        (stored as previous request)  (axi read request from previous)      write.isComplete = T
  //                                      [if swap out dirty]:                  isReady = F              [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        (axi write request)                                            isReady = F                                               isReady = T
  //                                                                                                     [else]:
  //                                                                                                       isReady = T
  //
  // state: ready                         [if swap out dirty]:                                           [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        refillForWriteAndWb                                            onlyWb                                                    ready
  //                                      [else]:                                                        [else]:
  //                                        refillForWrite                                                 ready
  // note:
  //   - Set reg `isRequestSent` to F before entering state for cache miss,
  //     then set reg `isRequestSent` to T after entering
  //   - `refillForWriteAndWb` is merged to `refillForWrite` with `isNeedWbReg`

  // Status-tag line:
  // 1.W      1.W      TagW
  // isValid  isDirty  Tag

  // Data line:
  // MemDataW ... MemDataW
  // data     ... data

  // Mem addr:
  // TagW  IndexW      ByteOffsetW
  // tag   queryIndex  byteOffset
  //
  // note: Lowest 2 [log2(wordLength / byteLength)] bits of byteOffset should be 0

  // Note: Number of RAMs is the same as the size of a set

  // Functions for calculation
  def byteOffsetFromMemAddr(addr: UInt) = addr(Param.Width.DCache._byteOffset - 1, 0)
  def dataIndexFromByteOffset(offset: UInt) =
    offset(Param.Width.DCache._byteOffset - 1, log2Ceil(wordLength / byteLength))
  def dataIndexFromMemAddr(addr: UInt) = dataIndexFromByteOffset(byteOffsetFromMemAddr(addr))
  def queryIndexFromMemAddr(addr: UInt) =
    addr(Param.Width.DCache._byteOffset + Param.Width.DCache._addr - 1, Param.Width.DCache._byteOffset)
  def tagFromMemAddr(addr: UInt) = addr(Width.Mem._addr - 1, Width.Mem._addr - Param.Width.DCache._tag)
  def toDataLine(line: UInt) = VecInit(
    line.asBools
      .grouped(Width.Mem._data)
      .toSeq
      .map(VecInit(_).asUInt)
  )
  def writeWithMask(oldData: UInt, newData: UInt, mask: UInt) = (newData & mask) | (oldData & (~mask).asUInt)

  // Debug: Prepare cache
  assert(debugAddrSeq.length == debugDataLineSeq.length)
  assert(debugAddrSeq.length == debugStatusTagSeq.length)
  assert(debugAddrSeq.length == debugSetNumSeq.length)
  val debugWriteNum = debugAddrSeq.length

  // RAMs for valid, dirty, and tag
  val statusTagRams = Seq.fill(Param.Count.DCache.setLen)(
    Module(
      new SimpleRam(
        Param.Count.DCache.sizePerRam,
        StatusTagBundle.width,
        isDebug,
        debugWriteNum
      )
    )
  )

  // RAMs for data line
  val dataLineRams = Seq.fill(Param.Count.DCache.setLen)(
    Module(
      new SimpleRam(
        Param.Count.DCache.sizePerRam,
        Param.Width.DCache._dataLine,
        isDebug,
        debugWriteNum
      )
    )
  )

  (statusTagRams ++ dataLineRams).foreach { ram =>
    ram.io              := DontCare
    ram.io.writePort.en := false.B // Fallback: Not write
  }

  // AXI master
  val axiMaster = Module(
    new BetterAxiMaster(
      readSize  = Param.Width.DCache._dataLine,
      writeSize = Param.Width.DCache._dataLine,
      id        = Param.Axi.Id.dCache
    )
  )
  axiMaster.io                   <> DontCare
  io.axiMasterPort               <> axiMaster.io.axi
  axiMaster.io.read.req.isValid  := false.B // Fallback: No request
  axiMaster.io.write.req.isValid := false.B // Fallback: No request

  // Random set index
  assert(isPow2(Param.Count.DCache.setLen))
  val randomNum = LFSR(log2Ceil(Param.Count.DCache.setLen) + 1)

  // Debug: Init cache
  if (isDebug) {
    val debugEnReg = RegNext(false.B, true.B)

    statusTagRams.zip(dataLineRams).foreach {
      case (tRam, dRam) =>
        (tRam.io.debugPorts, dRam.io.debugPorts) match {
          case (Some(tPorts), Some(dPorts)) =>
            tPorts.zip(dPorts).foreach {
              case (tPort, dPort) =>
                tPort.en := false.B
                dPort.en := false.B
            }
        }
    }
    debugAddrSeq.lazyZip(debugStatusTagSeq).lazyZip(debugDataLineSeq).lazyZip(debugSetNumSeq).zipWithIndex.foreach {
      case ((addr, st, dl, num), i) =>
        (statusTagRams(num).io.debugPorts, dataLineRams(num).io.debugPorts) match {
          case (Some(tPorts), Some(dPorts)) =>
            val tPort = tPorts(i)
            val dPort = dPorts(i)
            tPort.en   := debugEnReg
            tPort.addr := addr
            tPort.data := st
            dPort.en   := debugEnReg
            dPort.addr := addr
            dPort.data := dl
        }
    }
  }

  val stateReg  = RegInit(State.ready)
  val nextState = WireDefault(stateReg)
  stateReg := nextState // Fallback: Keep state

  val isReadValidReg = RegNext(false.B, false.B) // Fallback: Not valid
  io.accessPort.read.isValid := isReadValidReg

  val readDataReg = RegInit(0.U(Width.Mem.data))
  readDataReg             := readDataReg // Fallback: Keep data
  io.accessPort.read.data := readDataReg

  io.accessPort.isReady          := false.B // Fallback: Not ready
  io.accessPort.write.isComplete := false.B // Fallback: Not complete

  // Keep request and cache query information
  val lastReg = Reg(new Bundle {
    val memAddr        = UInt(Width.Mem.addr)
    val statusTagLines = Vec(Param.Count.DCache.setLen, new StatusTagBundle)
    val setIndex       = UInt(log2Ceil(Param.Count.DCache.setLen).W)
    val dataLine       = Vec(Param.Count.DCache.dataPerLine, UInt(Width.Mem.data))
    val writeData      = UInt(Width.Mem.data)
    val writeMask      = UInt(Width.Mem.data)
  })
  lastReg := lastReg // Fallback: Keep data
  val last = new Bundle {
    val selectedStatusTag = WireDefault(lastReg.statusTagLines(lastReg.setIndex))
    val wbMemAddr = WireDefault(
      Cat(
        lastReg.statusTagLines(lastReg.setIndex).tag,
        queryIndexFromMemAddr(lastReg.memAddr),
        byteOffsetFromMemAddr(lastReg.memAddr)
      )
    )
  }

  // Refill state regs
  val isNeedWbReg       = RegInit(false.B)
  val isReadReqSentReg  = RegInit(false.B)
  val isWriteReqSentReg = RegInit(false.B)
  isReadReqSentReg  := isReadReqSentReg // Fallback: Keep data
  isWriteReqSentReg := isWriteReqSentReg // Fallback: Keep data
  isNeedWbReg       := isNeedWbReg

  def handleWb(): Unit = {
    when(!isWriteReqSentReg) {
      // Stage 2.b/c.3, 3.1: Send write request

      axiMaster.io.write.req.isValid := true.B
      axiMaster.io.write.req.addr    := last.wbMemAddr
      axiMaster.io.write.req.data    := lastReg.dataLine.asUInt

      when(axiMaster.io.write.req.isReady) {
        // Next Stage 2.b/c.4, 3.2
        isWriteReqSentReg := true.B
      }
    }.otherwise {
      // Stage 2.b/c.4, 3.2: Wait for write complete
      // Note: When write complete, set `isNeedWbReg` to F

      when(axiMaster.io.write.res.isComplete) {
        isNeedWbReg := false.B
      }
    }
  }

  switch(stateReg) {
    // Note: Can accept request when in the second cycle of write (hit),
    //       as long as the write information is passed to cache query
    is(State.ready, State.write) {
      io.accessPort.isReady := true.B

      when(io.accessPort.isValid) {
        // Stage 1 and Stage 2.a: Cache query

        // Decode
        val memAddr    = WireDefault(io.accessPort.addr)
        val tag        = WireDefault(tagFromMemAddr(memAddr))
        val queryIndex = WireDefault(queryIndexFromMemAddr(memAddr))
        val byteOffset = WireDefault(byteOffsetFromMemAddr(memAddr))
        val dataIndex  = WireDefault(dataIndexFromByteOffset(byteOffset))

        // Read status-tag
        statusTagRams.foreach { ram =>
          ram.io.readPort.addr := queryIndex
        }
        val statusTagLines = Wire(Vec(Param.Count.DCache.setLen, new StatusTagBundle))
        statusTagLines.zip(statusTagRams.map(_.io.readPort.data)).foreach {
          case (line, data) =>
            line.isValid := data(StatusTagBundle.width - 1)
            line.isDirty := data(StatusTagBundle.width - 2)
            line.tag     := data(StatusTagBundle.width - 3, 0)
        }

        // Read data (for read and write)
        dataLineRams.foreach { ram =>
          ram.io.readPort.addr := queryIndex
        }
        val dataLines = WireDefault(VecInit(dataLineRams.map(_.io.readPort.data)))

        // Calculate if hit and select
        val isSelectedVec         = WireDefault(VecInit(statusTagLines.map(line => line.isValid && (line.tag === tag))))
        val setIndex              = WireDefault(OHToUInt(isSelectedVec))
        val selectedStatusTagLine = WireDefault(statusTagLines(setIndex))
        val selectedDataLine      = WireDefault(toDataLine(dataLines(setIndex)))
        val isCacheHit            = WireDefault(isSelectedVec.reduce(_ || _))

        // If writing, then also query from write info
        // Predefine write info for passing through to read
        val writeDataLine  = WireDefault(lastReg.dataLine)
        val writeStatusTag = WireDefault(last.selectedStatusTag)
        when(
          stateReg === State.write &&
            queryIndexFromMemAddr(lastReg.memAddr) === queryIndex &&
            last.selectedStatusTag.tag === tag
        ) {
          // Pass write status-tag and data line to read
          setIndex              := lastReg.setIndex
          selectedStatusTagLine := writeStatusTag
          selectedDataLine      := writeDataLine
        }

        // Save data for later use
        lastReg.memAddr        := memAddr
        lastReg.statusTagLines := statusTagLines
        lastReg.setIndex       := setIndex
        lastReg.dataLine       := selectedDataLine
        lastReg.writeData      := io.accessPort.write.data
        lastReg.writeMask      := io.accessPort.write.mask

        // Select data by data index from byte offset
        val selectedData = WireDefault(selectedDataLine(dataIndex))

        when(isCacheHit) {
          // Cache hit
          switch(io.accessPort.rw) {
            is(ReadWriteSel.read) {
              // Remember to use regs
              isReadValidReg := true.B
              readDataReg    := selectedData

              // Next Stage 1
              nextState := State.ready
            }
            is(ReadWriteSel.write) {
              // Next Stage 2.a
              nextState := State.write
            }
          }
        }.otherwise {
          // Cache miss
          isNeedWbReg := false.B // Fallback: No write back

          // Select a set to refill

          // First, select from invalid, if it can
          val isInvalidVec   = statusTagLines.map(!_.isValid)
          val isInvalidHit   = WireDefault(isInvalidVec.reduce(_ || _))
          val refillSetIndex = WireDefault(PriorityEncoder(isInvalidVec))
          when(!isInvalidHit) {
            // Second, select from not dirty, if it can
            val isNotDirtyVec = statusTagLines.map(!_.isDirty)
            val isNotDirtyHit = WireDefault(isInvalidVec.reduce(_ || _))
            refillSetIndex := PriorityEncoder(isNotDirtyVec)
            when(!isNotDirtyHit) {
              // Finally, select randomly (using LFSR)
              // Also, don't forget to write back (now there is no invalid and not-dirty)
              refillSetIndex := randomNum(log2Ceil(Param.Count.DCache.setLen) - 1, 0)
              isNeedWbReg    := true.B

              // Save data for later use
              lastReg.dataLine := toDataLine(dataLines(refillSetIndex))
            }
          }

          // Save data for later use
          lastReg.setIndex := refillSetIndex

          // Init refill state regs
          isReadReqSentReg  := false.B
          isWriteReqSentReg := false.B

          switch(io.accessPort.rw) {
            is(ReadWriteSel.read) {
              // Next Stage 2.b.1
              nextState := State.refillForRead
            }
            is(ReadWriteSel.write) {
              // Next Stage 2.c.1
              nextState := State.refillForWrite
            }
          }
        }

        when(stateReg === State.write) {
          // Stage 2.a: Write to cache (previous hit)

          // Substitute write data in data line, with mask
          val dataIndex = WireDefault(dataIndexFromMemAddr(lastReg.memAddr))
          val oldData   = WireDefault(lastReg.dataLine(dataIndex))
          writeDataLine(dataIndex) := writeWithMask(oldData, lastReg.writeData, lastReg.writeMask)

          val queryIndex = WireDefault(queryIndexFromMemAddr(lastReg.memAddr))

          // Set dirty bit
          writeStatusTag.isDirty := true.B

          // Write status-tag (especially dirty bit) to RAM
          statusTagRams.map(_.io.writePort).zipWithIndex.foreach {
            case (writePort, index) =>
              writePort.en   := index.U === lastReg.setIndex
              writePort.data := writeStatusTag.asUInt
              writePort.addr := queryIndex
          }

          // Write to data line RAM
          dataLineRams.map(_.io.writePort).zipWithIndex.foreach {
            case (writePort, index) =>
              writePort.en   := index.U === lastReg.setIndex
              writePort.data := writeDataLine.asUInt
              writePort.addr := queryIndex
          }

          // Mark write as complete
          io.accessPort.write.isComplete := true.B
        }
      }
    }

    is(State.refillForRead) {
      // Stage 2.b: Refill for read (previous miss)

      when(!isReadReqSentReg) {
        // Stage 2.b.1: Send read request

        axiMaster.io.read.req.isValid := true.B
        axiMaster.io.read.req.addr    := lastReg.memAddr

        when(axiMaster.io.read.req.isReady) {
          // Next Stage 2.b.2
          isReadReqSentReg := true.B
        }
      }.otherwise {
        // Stage 2.b.2: Wait for refill data line

        when(axiMaster.io.read.res.isValid) {
          val queryIndex = WireDefault(queryIndexFromMemAddr(lastReg.memAddr))
          val statusTag  = Wire(new StatusTagBundle)
          statusTag.isValid := true.B
          statusTag.isDirty := false.B
          statusTag.tag     := tagFromMemAddr(lastReg.memAddr)

          // Write status-tag to RAM
          statusTagRams.map(_.io.writePort).zipWithIndex.foreach {
            case (writePort, index) =>
              writePort.en   := index.U === lastReg.setIndex
              writePort.data := statusTag.asUInt
              writePort.addr := queryIndex
          }

          // Write to data line RAM
          dataLineRams.map(_.io.writePort).zipWithIndex.foreach {
            case (writePort, index) =>
              writePort.en   := index.U === lastReg.setIndex
              writePort.data := axiMaster.io.read.res.data
              writePort.addr := queryIndex
          }

          // Return read data
          val dataLine = WireDefault(toDataLine(axiMaster.io.read.res.data))
          val readData = WireDefault(dataLine(dataIndexFromMemAddr(lastReg.memAddr)))
          isReadValidReg := true.B
          readDataReg    := readData

          when(isNeedWbReg) {
            // Next Stage 3
            nextState := State.onlyWb
          }.otherwise {
            // Next Stage 1
            nextState := State.ready
          }
        }
      }

      // Handle writing back
      when(isNeedWbReg) {
        handleWb()
      }
    }

    is(State.refillForWrite) {
      // Stage 2.c: Refill for write (previous miss)

      when(!isReadReqSentReg) {
        // Stage 2.b.1: Send read request

        axiMaster.io.read.req.isValid := true.B
        axiMaster.io.read.req.addr    := lastReg.memAddr

        when(axiMaster.io.read.req.isReady) {
          // Next Stage 2.b.2
          isReadReqSentReg := true.B
        }
      }.otherwise {
        // Stage 2.b.2: Wait for refill data line

        when(axiMaster.io.read.res.isValid) {
          val queryIndex = WireDefault(queryIndexFromMemAddr(lastReg.memAddr))
          val statusTag  = Wire(new StatusTagBundle)
          statusTag.isValid := true.B
          statusTag.isDirty := true.B
          statusTag.tag     := tagFromMemAddr(lastReg.memAddr)

          // Write status-tag to RAM
          statusTagRams.map(_.io.writePort).zipWithIndex.foreach {
            case (writePort, index) =>
              writePort.en   := index.U === lastReg.setIndex
              writePort.data := statusTag.asUInt
              writePort.addr := queryIndex
          }

          // Write to data line RAM
          val dataIndex = WireDefault(dataIndexFromMemAddr(lastReg.memAddr))
          val dataLine  = WireDefault(toDataLine(axiMaster.io.read.res.data))
          val oldData   = WireDefault(toDataLine(axiMaster.io.read.res.data)(dataIndex))
          dataLine(dataIndex) := writeWithMask(oldData, lastReg.writeData, lastReg.writeMask)
          dataLineRams.map(_.io.writePort).zipWithIndex.foreach {
            case (writePort, index) =>
              writePort.en   := index.U === lastReg.setIndex
              writePort.data := dataLine.asUInt
              writePort.addr := queryIndex
          }

          // Mark write complete (in the same cycle)
          io.accessPort.write.isComplete := true.B

          when(isNeedWbReg) {
            // Next Stage 3
            nextState := State.onlyWb
          }.otherwise {
            // Next Stage 1
            nextState := State.ready
          }
        }
      }

      // Handle writing back
      when(isNeedWbReg) {
        handleWb()
      }
    }

    is(State.onlyWb) {
      // Stage 3: Wait for writing back complete

      handleWb()

      when(isWriteReqSentReg && axiMaster.io.write.res.isComplete) {
        // Next Stage 1
        nextState := State.ready
      }
    }
  }
}
