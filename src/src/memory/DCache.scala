package memory

import axi.BetterAxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import common.enums.ReadWriteSel
import memory.bundles.{CacheMaintenanceHandshakePort, MemAccessPort, StatusTagBundle}
import memory.enums.{DCacheState => State}
import spec._

class DCache(
  isDebug:           Boolean   = false,
  debugAddrSeq:      Seq[UInt] = Seq(),
  debugDataLineSeq:  Seq[UInt] = Seq(),
  debugStatusTagSeq: Seq[UInt] = Seq(),
  debugSetNumSeq:    Seq[Int]  = Seq())
    extends Module {
  val io = IO(new Bundle {
    val maintenancePort = new CacheMaintenanceHandshakePort
    val accessPort      = new MemAccessPort
    val axiMasterPort   = new AxiMasterInterface
  })

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

  def dataIndexFromMemAddr(addr: UInt) = addr(Param.Width.ICache._byteOffset - 1, log2Ceil(wordLength / byteLength))

  def queryIndexFromMemAddr(addr: UInt) =
    addr(Param.Width.DCache._byteOffset + Param.Width.DCache._addr - 1, Param.Width.DCache._byteOffset)

  def tagFromMemAddr(addr: UInt) = addr(Width.Mem._addr - 1, Width.Mem._addr - Param.Width.DCache._tag)

  def toDataLine(line: UInt) = VecInit(
    line.asBools
      .grouped(Width.Mem._data)
      .toSeq
      .map(VecInit(_).asUInt)
  )

  def toStatusTagLine(line: UInt) = {
    val bundle = Wire(new StatusTagBundle)
    bundle.isValid := line(StatusTagBundle.width - 1)
    bundle.isDirty := line(StatusTagBundle.width - 2)
    bundle.tag     := line(StatusTagBundle.width - 3, 0)
    bundle
  }

  def writeWithMask(oldData: UInt, newData: UInt, mask: UInt) = (newData & mask) | (oldData & (~mask).asUInt)

  def toWriteMaskBits(byteMask: UInt) = Cat(
    byteMask.asBools
      .map(Mux(_, "h_FF".U(byteLength.W), 0.U(byteLength.W)))
      .reverse
  )

  // Debug: Prepare cache
  assert(debugAddrSeq.length == debugDataLineSeq.length)
  assert(debugAddrSeq.length == debugStatusTagSeq.length)
  assert(debugAddrSeq.length == debugSetNumSeq.length)
  val debugWriteNum = debugAddrSeq.length

  // RAMs for valid, dirty, and tag
  val statusTagRams = Seq.fill(Param.Count.DCache.setLen)(
    Module(
      new BRam(
        Param.Count.DCache.sizePerRam,
        StatusTagBundle.width
      )
    )
  )

  // RAMs for data line
  val dataLineRams = Seq.fill(Param.Count.DCache.setLen)(
    Module(
      new BRam(
        Param.Count.DCache.sizePerRam,
        Param.Width.DCache._dataLine
      )
    )
  )

  (statusTagRams ++ dataLineRams).foreach { ram =>
    ram.io         := DontCare
    ram.io.isWrite := false.B // Fallback: Not write
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
//  if (isDebug) {
//    val debugEnReg = RegNext(false.B, true.B)
//
//    statusTagRams.zip(dataLineRams).foreach {
//      case (tRam, dRam) =>
//        (tRam.io.debugPorts, dRam.io.debugPorts) match {
//          case (Some(tPorts), Some(dPorts)) =>
//            tPorts.zip(dPorts).foreach {
//              case (tPort, dPort) =>
//                tPort.en := false.B
//                dPort.en := false.B
//            }
//        }
//      case (_, _) =>
//    }
//    debugAddrSeq.lazyZip(debugStatusTagSeq).lazyZip(debugDataLineSeq).lazyZip(debugSetNumSeq).zipWithIndex.foreach {
//      case ((addr, st, dl, num), i) =>
//        (statusTagRams(num).io.debugPorts, dataLineRams(num).io.debugPorts) match {
//          case (Some(tPorts), Some(dPorts)) =>
//            val tPort = tPorts(i)
//            val dPort = dPorts(i)
//            tPort.en   := debugEnReg
//            tPort.addr := addr
//            tPort.data := st
//            dPort.en   := debugEnReg
//            dPort.addr := addr
//            dPort.data := dl
//        }
//      case (_, _) =>
//    }
//  }

  val stateReg  = RegInit(State.ready)
  val nextState = WireDefault(stateReg)
  stateReg := nextState // Fallback: Keep state

  io.accessPort.req.isReady := false.B // Fallback: Not ready

  io.maintenancePort.isReady := false.B // Fallback: Not ready

  io.accessPort.res.isFailed := false.B // Fallback: Not failed

  io.accessPort.res.isComplete := false.B // Falback: Not complete

  val currentMemAddr = WireDefault(
    Mux(
      io.maintenancePort.client.isL1Valid,
      io.maintenancePort.client.addr,
      io.accessPort.req.client.addr
    )
  )

  val isHasReqReg     = RegNext(false.B, false.B) // Fallback: Not valid
  val readWriteReqReg = RegNext(io.accessPort.req.client.rw) // Fallback: Current R/W
  val reqMemAddr      = RegNext(currentMemAddr) // Fallback: Current memory access address
  val reqWriteData    = RegNext(io.accessPort.req.client.write.data) // Fallback: Current write data
  val reqWriteMask    = RegNext(toWriteMaskBits(io.accessPort.req.client.mask)) // Fallback: Current write mask

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

  // Maintenance write info regs
  val isMaintenanceInitWrite = RegNext(false.B, false.B)
  val isMaintenanceHit       = RegInit(false.B)
  isMaintenanceHit := isMaintenanceHit

  // Maintenance write-back info regs
  val setCountDownReg            = RegInit(0.U(log2Ceil(Param.Count.DCache.setLen).W))
  val isSetCountDownCompleteReg  = RegInit(false.B)
  val dataCountDownReg           = RegInit(0.U(log2Ceil(Param.Count.DCache.dataPerLine).W))
  val isDataCountDownCompleteReg = RegInit(false.B)
  setCountDownReg            := setCountDownReg
  isSetCountDownCompleteReg  := isSetCountDownCompleteReg
  dataCountDownReg           := dataCountDownReg
  isDataCountDownCompleteReg := isDataCountDownCompleteReg

  def handleWb(addr: UInt, data: UInt): Unit = {
    when(!isWriteReqSentReg) {
      // Stage 2.b/c.3, 3.1: Send write request

      axiMaster.io.write.req.isValid := true.B
      axiMaster.io.write.req.addr    := addr
      axiMaster.io.write.req.data    := data

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
    is(State.ready) {
      // Stage 1 and Stage 2.a: Read BRAM and cache query in two cycles

      io.accessPort.req.isReady  := !io.maintenancePort.client.isL1Valid // Fallback: Ready for request
      io.maintenancePort.isReady := true.B // Fallback: Ready for request

      // Step 1: BRAM read request
      val currentQueryIndex = WireDefault(queryIndexFromMemAddr(currentMemAddr))
      statusTagRams.foreach { ram =>
        ram.io.addr := currentQueryIndex
      }
      dataLineRams.foreach { ram =>
        ram.io.addr := currentQueryIndex
      }
      isHasReqReg := io.accessPort.req.client.isValid

      // Step 2: Read status-tag
      val statusTagLines = WireDefault(VecInit(statusTagRams.map(ram => toStatusTagLine(ram.io.dataOut))))

      // Step 2: Read data (for read and write)
      val dataLines = WireDefault(VecInit(dataLineRams.map(_.io.dataOut)))

      // Step 2: Decode
      val tag        = WireDefault(tagFromMemAddr(reqMemAddr))
      val queryIndex = WireDefault(queryIndexFromMemAddr(reqMemAddr))
      val dataIndex  = WireDefault(dataIndexFromMemAddr(reqMemAddr))

      // Step 2: Calculate if hit and select
      val isSelectedVec         = WireDefault(VecInit(statusTagLines.map(line => line.isValid && (line.tag === tag))))
      val setIndex              = WireDefault(OHToUInt(isSelectedVec))
      val selectedStatusTagLine = WireDefault(statusTagLines(setIndex))
      val selectedDataLine      = WireDefault(toDataLine(dataLines(setIndex)))
      val isCacheHit            = WireDefault(isSelectedVec.reduce(_ || _))

      // Step 2: Save data for later use
      lastReg.memAddr        := reqMemAddr
      lastReg.statusTagLines := statusTagLines
      lastReg.setIndex       := setIndex
      lastReg.dataLine       := selectedDataLine
      lastReg.writeData      := reqWriteData
      lastReg.writeMask      := reqWriteMask

      // Step 2: Select data by data index from byte offset
      val selectedData = WireDefault(selectedDataLine(dataIndex))

      // Step 2: Whether hit or not
      when(isHasReqReg) {
        when(isCacheHit) {
          // Cache hit
          switch(readWriteReqReg) {
            is(ReadWriteSel.read) {
              // Step 2: Read result in same cycle output
              io.accessPort.res.isComplete := true.B
              io.accessPort.res.read.data  := selectedData

              // Next Stage 1
              nextState := State.ready
            }
            is(ReadWriteSel.write) {
              // Step 2: Write to cache (now hit)
              io.accessPort.req.isReady  := false.B
              io.maintenancePort.isReady := false.B

              val writeDataLine  = WireDefault(selectedDataLine)
              val writeStatusTag = WireDefault(selectedStatusTagLine)

              // Substitute write data in data line, with mask
              val oldData = WireDefault(selectedDataLine(dataIndex))
              writeDataLine(dataIndex) := writeWithMask(oldData, reqWriteData, reqWriteMask)

              // Set dirty bit
              writeStatusTag.isDirty := true.B

              // Write status-tag (especially dirty bit) to RAM
              statusTagRams.zipWithIndex.foreach {
                case (ram, index) =>
                  ram.io.isWrite := index.U === setIndex
                  ram.io.dataIn  := writeStatusTag.asUInt
                  ram.io.addr    := queryIndex
              }

              // Write to data line RAM
              dataLineRams.zipWithIndex.foreach {
                case (ram, index) =>
                  ram.io.isWrite := index.U === setIndex
                  ram.io.dataIn  := writeDataLine.asUInt
                  ram.io.addr    := queryIndex
              }

              // Mark write as complete
              io.accessPort.res.isComplete := true.B

              // Next Stage 1
              nextState := State.ready
            }
          }
        }.otherwise {
          // Cache miss
          io.accessPort.req.isReady  := false.B
          io.maintenancePort.isReady := false.B
          isNeedWbReg                := false.B // Fallback: No write back

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

          switch(io.accessPort.req.client.rw) {
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
      }

      // Maintenance
      when(io.maintenancePort.client.isL1Valid) {
        isMaintenanceHit           := isCacheHit
        isSetCountDownCompleteReg  := false.B
        isDataCountDownCompleteReg := false.B
        setCountDownReg            := (Param.Count.DCache.setLen - 1).U
        dataCountDownReg           := (Param.Count.DCache.dataPerLine - 1).U
        isNeedWbReg                := true.B
        isWriteReqSentReg          := false.B

        when(io.maintenancePort.client.isInit) {
          isMaintenanceInitWrite := true.B

          // Next Stage 2.a*
          nextState := State.write // TODO: Refactor this
        }
        when(io.maintenancePort.client.isCoherentByIndex) {
          // Next Stage: Maintenance for all sets
          nextState := State.maintenanceAll
        }
        when(io.maintenancePort.client.isCoherentByHit) {
          // Next Stage: Maintenance only for hit
          nextState := State.maintenanceHit
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
          statusTagRams.zipWithIndex.foreach {
            case (ram, index) =>
              ram.io.isWrite := index.U === lastReg.setIndex
              ram.io.dataIn  := statusTag.asUInt
              ram.io.addr    := queryIndex
          }

          // Write to data line RAM
          dataLineRams.zipWithIndex.foreach {
            case (ram, index) =>
              ram.io.isWrite := index.U === lastReg.setIndex
              ram.io.dataIn  := axiMaster.io.read.res.data
              ram.io.addr    := queryIndex
          }

          // Return read data
          val dataLine = WireDefault(toDataLine(axiMaster.io.read.res.data))
          val readData = WireDefault(dataLine(dataIndexFromMemAddr(lastReg.memAddr)))
          io.accessPort.res.isComplete := true.B
          io.accessPort.res.isFailed   := axiMaster.io.read.res.isFailed
          io.accessPort.res.read.data  := readData

          when(isNeedWbReg) {
            // Next Stage 3
            nextState := State.onlyWb
          }.otherwise {
            // Next Stage 1
            // TODO: Add one more cycle for return read data
            nextState := State.ready
          }
        }
      }

      // Handle writing back
      when(isNeedWbReg) {
        handleWb(last.wbMemAddr, lastReg.dataLine.asUInt)
      }
    }

    is(State.refillForWrite) {
      // Stage 2.c: Refill for write (previous miss)

      when(!isReadReqSentReg) {
        // Stage 2.b.1: Send read request

        axiMaster.io.read.req.isValid := true.B
        axiMaster.io.read.req.addr := Cat(
          lastReg.memAddr(Width.Mem._addr - 1, Param.Width.DCache._byteOffset),
          0.U(Param.Width.DCache.byteOffset)
        )

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
          statusTagRams.zipWithIndex.foreach {
            case (ram, index) =>
              ram.io.isWrite := index.U === lastReg.setIndex
              ram.io.dataIn  := statusTag.asUInt
              ram.io.addr    := queryIndex
          }

          // Write to data line RAM
          val dataIndex = WireDefault(dataIndexFromMemAddr(lastReg.memAddr))
          val dataLine  = WireDefault(toDataLine(axiMaster.io.read.res.data))
          val oldData   = WireDefault(toDataLine(axiMaster.io.read.res.data)(dataIndex))
          dataLine(dataIndex) := writeWithMask(oldData, lastReg.writeData, lastReg.writeMask)
          dataLineRams.zipWithIndex.foreach {
            case (ram, index) =>
              ram.io.isWrite := index.U === lastReg.setIndex
              ram.io.dataIn  := dataLine.asUInt
              ram.io.addr    := queryIndex
          }

          // Mark write complete (in the same cycle)
          io.accessPort.res.isComplete := true.B
          io.accessPort.res.isFailed   := axiMaster.io.read.res.isFailed

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
        handleWb(last.wbMemAddr, lastReg.dataLine.asUInt)
      }
    }

    is(State.onlyWb) {
      // Stage 3: Wait for writing back complete

      handleWb(last.wbMemAddr, lastReg.dataLine.asUInt)

      when(isWriteReqSentReg && axiMaster.io.write.res.isComplete) {
        // Next Stage 1
        nextState := State.ready
      }
    }

    is(State.maintenanceHit) {
      // Maintenance: Coherent by hit

      val queryIndex = WireDefault(queryIndexFromMemAddr(lastReg.memAddr))
      val writeBackAddr = WireDefault(
        Cat(
          tagFromMemAddr(lastReg.memAddr),
          queryIndex,
          dataCountDownReg,
          0.U(log2Ceil(wordLength / byteLength).W)
        )
      )
      val dataLines        = WireDefault(VecInit(dataLineRams.map(_.io.dataIn)))
      val selectedDataLine = WireDefault(dataLines(lastReg.setIndex))

      dataLineRams.map(_.io.addr).foreach(_ := queryIndex)

      when(isMaintenanceHit) {
        when(last.selectedStatusTag.isDirty) {
          when(isNeedWbReg) {
            when(dataCountDownReg === 0.U) {
              isDataCountDownCompleteReg := true.B
            }
            handleWb(writeBackAddr, selectedDataLine(dataCountDownReg))
          }.otherwise {
            when(isDataCountDownCompleteReg) {
              statusTagRams.zipWithIndex.foreach {
                case (ram, index) =>
                  ram.io.isWrite := index.U === lastReg.setIndex
                  ram.io.dataIn  := 0.U
                  ram.io.addr    := queryIndex
              }
              // Next Stage 1
              nextState := State.ready
            }.otherwise {
              dataCountDownReg := dataCountDownReg - 1.U
              isNeedWbReg      := true.B
            }
          }
        }.otherwise {
          statusTagRams.zipWithIndex.foreach {
            case (ram, index) =>
              ram.io.isWrite := index.U === lastReg.setIndex
              ram.io.dataIn  := 0.U
              ram.io.addr    := queryIndex
          }

          // Next Stage 1
          nextState := State.ready
        }
      }.otherwise {
        // Next Stage 1
        nextState := State.ready
      }
    }

    is(State.maintenanceAll) {
      // Maintenance: Coherent by index

      val queryIndex = WireDefault(queryIndexFromMemAddr(lastReg.memAddr))
      val writeBackAddr = WireDefault(
        Cat(
          tagFromMemAddr(lastReg.memAddr),
          queryIndex,
          dataCountDownReg,
          0.U(log2Ceil(wordLength / byteLength).W)
        )
      )
      val dataLines        = WireDefault(VecInit(dataLineRams.map(_.io.dataOut)))
      val selectedDataLine = WireDefault(dataLines(setCountDownReg))
      val statusTagLines   = WireDefault(VecInit(statusTagRams.map(ram => toStatusTagLine(ram.io.dataOut))))

      dataLineRams.map(_.io.addr).foreach(_ := queryIndex)
      statusTagRams.map(_.io.addr).foreach(_ := queryIndex)

      when(isSetCountDownCompleteReg) {
        // Next Stage 1
        nextState := State.ready
      }.otherwise {
        when(statusTagLines(setCountDownReg).isDirty) {
          when(isNeedWbReg) {
            when(dataCountDownReg === 0.U) {
              isDataCountDownCompleteReg := true.B
            }
            handleWb(writeBackAddr, selectedDataLine(dataCountDownReg))
          }.otherwise {
            when(isDataCountDownCompleteReg) {
              statusTagRams.zipWithIndex.foreach {
                case (ram, index) =>
                  ram.io.isWrite := index.U === setCountDownReg
                  ram.io.dataIn  := 0.U
                  ram.io.addr    := queryIndex
              }

              when(setCountDownReg === 0.U) {
                isSetCountDownCompleteReg := true.B
              }.otherwise {
                setCountDownReg := setCountDownReg - 1.U
              }
              dataCountDownReg := (Param.Count.DCache.dataPerLine - 1).U
              isNeedWbReg      := true.B
            }.otherwise {
              dataCountDownReg := dataCountDownReg - 1.U
              isNeedWbReg      := true.B
            }
          }
        }.otherwise {
          statusTagRams.zipWithIndex.foreach {
            case (ram, index) =>
              ram.io.isWrite := index.U === setCountDownReg
              ram.io.dataIn  := 0.U
              ram.io.addr    := queryIndex
          }

          when(setCountDownReg === 0.U) {
            isSetCountDownCompleteReg := true.B
          }.otherwise {
            setCountDownReg := setCountDownReg - 1.U
          }
          dataCountDownReg := (Param.Count.DCache.dataPerLine - 1).U
          isNeedWbReg      := true.B
        }
      }
    }
  }
}
