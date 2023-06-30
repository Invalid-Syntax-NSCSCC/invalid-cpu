package memory

import axi.BetterAxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import frontend.bundles.ICacheAccessPort
import memory.bundles.{CacheMaintenanceHandshakePort, ICacheStatusTagBundle}
import memory.enums.{ICacheState => State}
import spec._

class ICache(
  isDebug:           Boolean   = false,
  debugAddrSeq:      Seq[UInt] = Seq(),
  debugDataLineSeq:  Seq[UInt] = Seq(),
  debugStatusTagSeq: Seq[UInt] = Seq(),
  debugSetNumSeq:    Seq[Int]  = Seq())
    extends Module {
  val io = IO(new Bundle {
    val maintenancePort = new CacheMaintenanceHandshakePort
    val accessPort      = new ICacheAccessPort
    val axiMasterPort   = new AxiMasterInterface
  })

  // Read cache hit diagram:
  // clock: ^_____________________________^___________________
  //        isReady = T                   isReady = T
  //        isValid = T                   read.isValid = T
  //                                      read.data = *data*
  //        (stored as previous request)
  //
  // state: ready                         ready

  // Read cache miss diagram:
  // clock: ^_____________________________^_________________________________^...^___________________^_____________________________________________________^...^______________
  //        isReady = T                   isReady = F                           (get axi data)      read.data = *data*
  //        isValid = T                                                         (fill cache)        read.isValid = T
  //        rw = Read
  //        (stored as previous request)  (axi read request from previous)

  //
  // state: ready                         refillForRead                                     ready
  //

  // note:
  //   - Set reg `isRequestSent` to F before entering state for cache miss,
  //     then set reg `isRequestSent` to T after entering

  // Status-tag line:
  // 1.W       TagW
  // isValid   Tag

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
  def dataIndexFromMemAddr(addr: UInt) = addr(Param.Width.ICache._byteOffset - 1, log2Ceil(wordLength / byteLength))

  def queryIndexFromMemAddr(addr: UInt) =
    addr(Param.Width.ICache._byteOffset + Param.Width.ICache._addr - 1, Param.Width.ICache._byteOffset)

  def tagFromMemAddr(addr: UInt) = addr(Width.Mem._addr - 1, Width.Mem._addr - Param.Width.ICache._tag)

  def toDataLine(line: UInt) = VecInit(
    line.asBools
      .grouped(Width.Mem._data)
      .toSeq
      .map(VecInit(_).asUInt)
  )

  def toStatusTagLine(line: UInt) = {
    val bundle = Wire(new ICacheStatusTagBundle)
    bundle.isValid := line(ICacheStatusTagBundle.width - 1)
    bundle.tag     := line(ICacheStatusTagBundle.width - 2, 0)
    bundle
  }

  // Debug: Prepare cache
  assert(debugAddrSeq.length == debugDataLineSeq.length)
  assert(debugAddrSeq.length == debugStatusTagSeq.length)
  assert(debugAddrSeq.length == debugSetNumSeq.length)
  val debugWriteNum = debugAddrSeq.length

  // RAMs for valid and tag
  val statusTagRams = Seq.fill(Param.Count.ICache.setLen)(
    Module(
      new VBRam(
        Param.Count.ICache.sizePerRam,
        ICacheStatusTagBundle.width
      )
    )
  )

  // RAMs for data line
  val dataLineRams = Seq.fill(Param.Count.ICache.setLen)(
    Module(
      new VBRam(
        Param.Count.ICache.sizePerRam,
        Param.Width.ICache._dataLine
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
      readSize  = Param.Width.ICache._dataLine,
      writeSize = Param.Width.ICache._dataLine,
      id        = Param.Axi.Id.iCache
    )
  )
  axiMaster.io                   <> DontCare
  io.axiMasterPort               <> axiMaster.io.axi
  axiMaster.io.read.req.isValid  := false.B // Fallback: No request
  axiMaster.io.write.req.isValid := false.B // Fallback: No request

  // Random set index
  assert(isPow2(Param.Count.ICache.setLen))
  val randomNum = LFSR(log2Ceil(Param.Count.ICache.setLen) + 1)

  val stateReg  = RegInit(State.ready)
  val nextState = WireDefault(stateReg)
  stateReg := nextState // Fallback: Keep state

  val isCompleteReg = RegInit(false.B)
  isCompleteReg := isCompleteReg
  val readDataVecReg = RegInit(VecInit(Seq.fill(Param.fetchInstMaxNum)(0.U(Width.Mem.data))))
  readDataVecReg := readDataVecReg

  io.accessPort.req.isReady := false.B // Fallback: Not ready

  io.maintenancePort.isReady := false.B // Fallback: Not ready

  io.accessPort.res.isFailed := false.B // Fallback: Not failed

  io.accessPort.res.isComplete := isCompleteReg // Fallback: Keep status

  io.accessPort.res.read.dataVec := readDataVecReg // Fallback: Keep data

  val currentMemAddr = WireDefault(
    Mux(
      io.maintenancePort.client.control.isL1Valid,
      io.maintenancePort.client.addr,
      Cat(
        io.accessPort.req.client
          .addr(spec.Width.Mem._addr, Param.Width.ICache._fetchOffset),
        0.U(Param.Width.ICache._fetchOffset.W)
      )
    )
  )

  val isHasReqReg = RegNext(false.B, false.B) // Fallback: Not valid
  val reqMemAddr  = RegNext(currentMemAddr) // Fallback: Current memory access address

  // Keep request and cache query information
  val lastReg = Reg(new Bundle {
    val memAddr        = UInt(Width.Mem.addr)
    val statusTagLines = Vec(Param.Count.ICache.setLen, new ICacheStatusTagBundle)
    val setIndex       = UInt(log2Ceil(Param.Count.ICache.setLen).W)
    val dataLine       = Vec(Param.Count.ICache.dataPerLine, UInt(Width.Mem.data))
  })
  lastReg := lastReg // Fallback: Keep data

  // Refill state regs
  val isReadReqSentReg = RegInit(false.B)
  isReadReqSentReg := isReadReqSentReg // Fallback: Keep data

  switch(stateReg) {
    // Note: Can accept request when in the second cycle of write (hit),
    //       as long as the write information is passed to cache query
    is(State.ready) {
      // Stage 1 and Stage 2.a: Read BRAM and cache query in two cycles

      io.accessPort.req.isReady  := !io.maintenancePort.client.control.isL1Valid // Fallback: Ready for request
      io.maintenancePort.isReady := true.B

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
      val tag       = WireDefault(tagFromMemAddr(reqMemAddr))
      val dataIndex = WireDefault(dataIndexFromMemAddr(reqMemAddr))

      // Step 2: Calculate if hit and select
      val isSelectedVec    = WireDefault(VecInit(statusTagLines.map(line => line.isValid && (line.tag === tag))))
      val setIndex         = WireDefault(OHToUInt(isSelectedVec))
      val selectedDataLine = WireDefault(toDataLine(dataLines(setIndex)))
      val isCacheHit       = WireDefault(isSelectedVec.reduce(_ || _))

      // Step 2: Save data for later use
      lastReg.memAddr        := reqMemAddr
      lastReg.statusTagLines := statusTagLines
      lastReg.setIndex       := setIndex
      lastReg.dataLine       := selectedDataLine

      // Step 2: Select data by data index from byte offset
      val selectDataVec = WireDefault(VecInit(Seq.fill(Param.fetchInstMaxNum)(0.U(spec.Width.Mem.data))))
      Seq.range(0, Param.fetchInstMaxNum).map { fetchIndex =>
        val fetchOffsetIndex = dataIndex + fetchIndex.asUInt(log2Ceil(Param.Count.ICache.dataPerLine).W)
        val readData         = WireDefault(selectedDataLine(fetchOffsetIndex))
        selectDataVec(fetchIndex) := readData
        selectDataVec
      }

      // Step 2: Whether hit or not
      when(isHasReqReg) {
        when(isCacheHit) {
          // Cache hit

          // Step 2: Read result in same cycle output
          io.accessPort.res.isComplete   := true.B
          io.accessPort.res.read.dataVec := selectDataVec
          isCompleteReg                  := true.B
          readDataVecReg                 := selectDataVec

          // Next Stage 1
          nextState := State.ready
        }.otherwise {
          // Cache miss

          io.accessPort.req.isReady    := false.B
          io.maintenancePort.isReady   := false.B
          io.accessPort.res.isComplete := false.B
          isCompleteReg                := false.B

          // Select a set to refill

          // First, select from invalid, if it can
          val isInvalidVec   = statusTagLines.map(!_.isValid)
          val isInvalidHit   = WireDefault(isInvalidVec.reduce(_ || _))
          val refillSetIndex = WireDefault(PriorityEncoder(isInvalidVec))
          when(!isInvalidHit) {
            // Finally, select randomly (using LFSR)
            refillSetIndex := randomNum(log2Ceil(Param.Count.ICache.setLen) - 1, 0)

            // Save data for later use
            lastReg.dataLine := toDataLine(dataLines(refillSetIndex))
          }

          // Save data for later use
          lastReg.setIndex := refillSetIndex

          // Init refill state regs
          isReadReqSentReg := false.B

          // Next Stage 2.b.1
          nextState := State.refillForRead
        }
      }

      // Maintenance
      when(io.maintenancePort.client.control.isL1Valid) {
        when(io.maintenancePort.client.control.isInit) {
          // Next Stage: Maintenance for all sets
          nextState := State.maintenanceAll
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
          val statusTag  = Wire(new ICacheStatusTagBundle)
          statusTag.isValid := true.B
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
          val dataLine       = WireDefault(toDataLine(axiMaster.io.read.res.data))
          val dataStartIndex = WireDefault(dataIndexFromMemAddr(lastReg.memAddr))
          val readDataVec    = WireDefault(VecInit(Seq.fill(Param.fetchInstMaxNum)(0.U(spec.Width.Mem.data))))
          Seq.range(0, Param.fetchInstMaxNum).map { fetchIndex =>
            val fetchOffsetIndex = dataStartIndex + fetchIndex.asUInt(log2Ceil(Param.Count.ICache.dataPerLine).W)
            val readData         = WireDefault(dataLine(fetchOffsetIndex))
            readDataVec(fetchIndex) := readData
            readDataVec
          }

          // TODO: Add one more cycle for return read data
          io.accessPort.res.isComplete   := true.B
          io.accessPort.res.isFailed     := axiMaster.io.read.res.isFailed
          io.accessPort.res.read.dataVec := readDataVec
          isCompleteReg                  := true.B
          readDataVecReg                 := readDataVec
          // TODO: `isFailedReg`

          // Next Stage 1
          nextState := State.ready
        }
      }
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

      statusTagRams.zipWithIndex.foreach {
        case (ram, index) =>
          ram.io.isWrite := index.U === lastReg.setIndex
          ram.io.dataIn  := 0.U
          ram.io.addr    := queryIndex
      }

      // Next Stage 1
      nextState := State.ready
    }

    is(State.maintenanceAll) {
      // Maintenance: Coherent by index

      val queryIndex = WireDefault(queryIndexFromMemAddr(reqMemAddr))

      statusTagRams.foreach { ram =>
        ram.io.isWrite := true.B
        ram.io.dataIn  := 0.U
        ram.io.addr    := queryIndex
      }

      // Next Stage 1
      nextState := State.ready
    }
  }
}
