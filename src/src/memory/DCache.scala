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
      new VSingleBRam(
        Param.Count.DCache.sizePerRam,
        StatusTagBundle.width
      )
    )
  )

  // RAMs for data line
  val dataLineRams = Seq.fill(Param.Count.DCache.setLen)(
    Module(
      new VSingleBRam(
        Param.Count.DCache.sizePerRam,
        Param.Width.DCache._dataLine
      )
    )
  )

  statusTagRams.foreach { ram =>
    ram.io         := DontCare
    ram.io.isWrite := false.B // Fallback: Not write
  }

  dataLineRams.foreach { ram =>
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

  val stateReg  = RegInit(State.ready)
  val nextState = WireDefault(stateReg)
  stateReg := nextState // Fallback: Keep state

  val isCompleteReg = RegInit(false.B)
  isCompleteReg := isCompleteReg
  val readDataReg = Reg(UInt(Width.Mem.data))
  readDataReg := readDataReg

  io.accessPort.req.isReady := false.B // Fallback: Not ready

  io.maintenancePort.isReady := false.B // Fallback: Not ready

  io.accessPort.res.isFailed := false.B // Fallback: Not failed

  io.accessPort.res.isComplete := isCompleteReg // Fallback: Keep status

  io.accessPort.res.read.data := readDataReg // Fallback: Keep data

  val currentMemAddr = WireDefault(
    Mux(
      io.maintenancePort.client.control.isL1Valid,
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
  val isNeedWbReg           = RegInit(false.B)
  val isReadReqSentReg      = RegInit(false.B)
  val isWriteBackReqSentReg = RegInit(false.B)
  isReadReqSentReg      := isReadReqSentReg // Fallback: Keep data
  isWriteBackReqSentReg := isWriteBackReqSentReg // Fallback: Keep data
  isNeedWbReg           := isNeedWbReg

  // Maintenance write-back info regs
  val setCountDownReg  = RegInit(0.U(log2Ceil(Param.Count.DCache.setLen).W))
  val dataCountDownReg = RegInit(0.U(log2Ceil(Param.Count.DCache.dataPerLine).W))
  setCountDownReg  := setCountDownReg
  dataCountDownReg := dataCountDownReg

  val isSetCountDownZero      = WireDefault(setCountDownReg === 0.U)
  val isDataCountDownComplete = WireDefault(dataCountDownReg === 0.U)

  def handleWb(addr: UInt, data: UInt): Unit = {
    when(!isWriteBackReqSentReg) {
      // Stage 2.b/c.3, 3.1: Send write request

      axiMaster.io.write.req.isValid := true.B
      axiMaster.io.write.req.addr    := addr
      axiMaster.io.write.req.data    := data

      when(axiMaster.io.write.req.isReady) {
        // Next Stage 2.b/c.4, 3.2
        isWriteBackReqSentReg := true.B
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

      io.accessPort.req.isReady  := !io.maintenancePort.client.control.isL1Valid // Fallback: Ready for request
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
              isCompleteReg                := true.B
              readDataReg                  := selectedData

              // Next Stage 1
              nextState := State.ready
            }
            is(ReadWriteSel.write) {
              // Step 2: Write to cache (now hit)
              io.accessPort.req.isReady  := false.B
              io.maintenancePort.isReady := false.B
              isHasReqReg                := false.B

              val writeStatusTag = WireDefault(selectedStatusTagLine)

              // Substitute write data in data line, with mask
              val oldData = WireDefault(selectedDataLine(dataIndex))
              val newData = WireDefault(writeWithMask(oldData, reqWriteData, reqWriteMask))
              val writeDataLine = WireDefault(VecInit(selectedDataLine.zipWithIndex.map {
                case (data, index) =>
                  Mux(index.U === dataIndex, newData, data)
              }))

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
              isCompleteReg                := true.B

              // Next Stage 1
              nextState := State.ready
            }
          }
        }.otherwise {
          // Cache miss

          io.accessPort.req.isReady    := false.B
          io.maintenancePort.isReady   := false.B
          io.accessPort.res.isComplete := false.B
          isCompleteReg                := false.B
          isNeedWbReg                  := false.B // Fallback: No write back

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
          isReadReqSentReg      := false.B
          isWriteBackReqSentReg := false.B

          switch(readWriteReqReg) {
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
      setCountDownReg  := (Param.Count.DCache.setLen - 1).U
      dataCountDownReg := (Param.Count.DCache.dataPerLine - 1).U

      when(io.maintenancePort.client.control.isL1Valid) {
        isNeedWbReg           := true.B
        isWriteBackReqSentReg := false.B

        when(io.maintenancePort.client.control.isInit) {
          // Next Stage: Maintenance for all sets (no write-back)
          nextState := State.maintenanceInit
        }
        when(io.maintenancePort.client.control.isCoherentByIndex) {
          // Next Stage: Maintenance for all sets
          nextState := State.maintenanceAll
        }
        when(io.maintenancePort.client.control.isCoherentByHit) {
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
          isCompleteReg                := true.B
          readDataReg                  := readData
          // TODO: `isFailedReg`

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
          val oldData   = WireDefault(dataLine(dataIndex))
          val newData   = WireDefault(writeWithMask(oldData, lastReg.writeData, lastReg.writeMask))
          val writeDataLine = WireDefault(VecInit(dataLine.zipWithIndex.map {
            case (data, index) =>
              Mux(index.U === dataIndex, newData, data)
          }))
          dataLineRams.zipWithIndex.foreach {
            case (ram, index) =>
              ram.io.isWrite := index.U === lastReg.setIndex
              ram.io.dataIn  := writeDataLine.asUInt
              ram.io.addr    := queryIndex
          }

          // Mark write complete (in the same cycle)
          io.accessPort.res.isComplete := true.B
          io.accessPort.res.isFailed   := axiMaster.io.read.res.isFailed
          isCompleteReg                := true.B

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

      when(isWriteBackReqSentReg && axiMaster.io.write.res.isComplete) {
        // Next Stage 1
        nextState := State.ready
      }
    }

    is(State.maintenanceInit) {
      // Maintenance: Init cache line

      val queryIndex = WireDefault(queryIndexFromMemAddr(lastReg.memAddr))

      statusTagRams.foreach { ram =>
        ram.io.isWrite := true.B
        ram.io.dataIn  := 0.U
        ram.io.addr    := queryIndex
      }

      // Next Stage 1
      nextState := State.ready
    }

    is(State.maintenanceHit) {
      val queryIndex = WireDefault(queryIndexFromMemAddr(reqMemAddr))

      // Step 2: Read status-tag
      val statusTagLines = WireDefault(VecInit(statusTagRams.map(ram => toStatusTagLine(ram.io.dataOut))))

      // Step 2: Read data request (for write-back)
      dataLineRams.foreach { ram =>
        ram.io.addr := queryIndex
      }

      // Step 2: Decode
      val tag = WireDefault(tagFromMemAddr(reqMemAddr))

      // Step 2: Calculate if hit and select
      val isSelectedVec = WireDefault(VecInit(statusTagLines.map(line => line.isValid && (line.tag === tag))))
      val setIndex      = WireDefault(OHToUInt(isSelectedVec))
      val isCacheHit    = WireDefault(isSelectedVec.reduce(_ || _))

      // Step 2: Save data for later use
      lastReg.memAddr        := reqMemAddr
      lastReg.statusTagLines := statusTagLines
      lastReg.setIndex       := setIndex

      when(isCacheHit) {
        // Next Stage: Coherent by hit
        nextState := State.maintenanceOne
      }.otherwise {
        // Next Stage 1
        nextState := State.ready
      }
    }

    is(State.maintenanceOne) {
      // Maintenance: Coherent by hit

      val queryIndex = WireDefault(queryIndexFromMemAddr(lastReg.memAddr))

      dataLineRams.foreach { ram =>
        ram.io.addr := queryIndex
      }

      val writeBackAddr = WireDefault(
        Cat(
          tagFromMemAddr(lastReg.memAddr),
          queryIndex,
          dataCountDownReg,
          0.U(log2Ceil(wordLength / byteLength).W)
        )
      )
      val dataLines        = WireDefault(VecInit(dataLineRams.map(_.io.dataOut))) // Delay for 1 cycle
      val selectedDataLine = WireDefault(dataLines(lastReg.setIndex))

      dataLineRams.map(_.io.addr).foreach(_ := queryIndex)

      when(last.selectedStatusTag.isDirty) {
        when(isNeedWbReg) {
          handleWb(writeBackAddr, selectedDataLine(dataCountDownReg))
        }.otherwise {
          when(isDataCountDownComplete) {
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
    }

    is(State.maintenanceAll) {
      // Maintenance: Coherent by index

      reqMemAddr := reqMemAddr

      val queryIndex = WireDefault(queryIndexFromMemAddr(reqMemAddr))
      val writeBackAddr = WireDefault(
        Cat(
          tagFromMemAddr(reqMemAddr),
          queryIndex,
          dataCountDownReg,
          0.U(log2Ceil(wordLength / byteLength).W)
        )
      )
      val dataLines        = WireDefault(VecInit(dataLineRams.map(_.io.dataOut))) // Delay for 1 cycle
      val selectedDataLine = WireDefault(dataLines(setCountDownReg))
      val statusTagLines   = WireDefault(VecInit(statusTagRams.map(ram => toStatusTagLine(ram.io.dataOut))))

      dataLineRams.map(_.io.addr).foreach(_ := queryIndex)

      when(statusTagLines(setCountDownReg).isDirty) {
        when(isNeedWbReg) {
          handleWb(writeBackAddr, selectedDataLine(dataCountDownReg))
        }.otherwise {
          when(isDataCountDownComplete) {
            when(!isSetCountDownZero) {
              setCountDownReg := setCountDownReg - 1.U
            }

            statusTagRams.zipWithIndex.foreach {
              case (ram, index) =>
                ram.io.isWrite := index.U === setCountDownReg
                ram.io.dataIn  := 0.U
                ram.io.addr    := queryIndex
            }

            dataCountDownReg := (Param.Count.DCache.dataPerLine - 1).U

            when(isSetCountDownZero) {
              // Next Stage 1
              nextState := State.ready
            }.otherwise {
              isNeedWbReg := true.B
            }
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

        dataCountDownReg := (Param.Count.DCache.dataPerLine - 1).U

        when(isSetCountDownZero) {
          // Next Stage 1
          nextState := State.ready
        }.otherwise {
          setCountDownReg := setCountDownReg - 1.U
          isNeedWbReg     := true.B
        }
      }
    }
  }
}
