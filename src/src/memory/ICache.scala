package memory

import axi.BetterAxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import common.enums.ReadWriteSel
import memory.bundles.ICacheStatusTagBundle
import memory.enums.{ICacheState => State}
import spec._
import frontend.bundles.ICacheAccessPort

class ICache(
  isDebug:           Boolean   = false,
  debugAddrSeq:      Seq[UInt] = Seq(),
  debugDataLineSeq:  Seq[UInt] = Seq(),
  debugStatusTagSeq: Seq[UInt] = Seq(),
  debugSetNumSeq:    Seq[Int]  = Seq())
    extends Module {
  val io = IO(new Bundle {
    val iCacheAccessPort = new ICacheAccessPort
    val axiMasterPort    = new AxiMasterInterface
  })

  // TODO: Remove DontCare
  io <> DontCare

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
  def byteOffsetFromMemAddr(addr: UInt) = addr(Param.Width.ICache._byteOffset - 1, 0)

  def dataIndexFromByteOffset(offset: UInt) =
    offset(Param.Width.ICache._byteOffset - 1, log2Ceil(wordLength / byteLength))

  def dataIndexFromMemAddr(addr: UInt) = dataIndexFromByteOffset(byteOffsetFromMemAddr(addr))

  def queryIndexFromMemAddr(addr: UInt) =
    addr(Param.Width.ICache._byteOffset + Param.Width.ICache._addr - 1, Param.Width.ICache._byteOffset)

  def tagFromMemAddr(addr: UInt) = addr(Width.Mem._addr - 1, Width.Mem._addr - Param.Width.ICache._tag)

  def toDataLine(line: UInt) = VecInit(
    line.asBools
      .grouped(Width.Mem._data)
      .toSeq
      .map(VecInit(_).asUInt)
  )

  // Debug: Prepare cache
  assert(debugAddrSeq.length == debugDataLineSeq.length)
  assert(debugAddrSeq.length == debugStatusTagSeq.length)
  assert(debugAddrSeq.length == debugSetNumSeq.length)
  val debugWriteNum = debugAddrSeq.length

  // RAMs for valid and tag
  val statusTagRams = Seq.fill(Param.Count.ICache.setLen)(
    Module(
      new SimpleRam(
        Param.Count.ICache.sizePerRam,
        ICacheStatusTagBundle.width,
        isDebug,
        debugWriteNum
      )
    )
  )

  // RAMs for data line
  val dataLineRams = Seq.fill(Param.Count.ICache.setLen)(
    Module(
      new SimpleRam(
        Param.Count.ICache.sizePerRam,
        Param.Width.ICache._dataLine,
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

  io.iCacheAccessPort.req.isReady := false.B // Fallback: Not ready

  io.iCacheAccessPort.res.isFailed := false.B // Fallback: Not failed

  val isReadValidReg  = RegNext(false.B, false.B) // Fallback: Not valid
  val isReadFailedReg = RegNext(false.B, false.B) // Fallback: Not failed
  io.iCacheAccessPort.res.isComplete := isReadValidReg

  val readDataReg = RegInit(0.U(Width.Mem.data))
  readDataReg                       := readDataReg // Fallback: Keep data
  io.iCacheAccessPort.res.read.data := readDataReg

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
      io.iCacheAccessPort.req.isReady := true.B

      // Stage 1 and Stage 2.a: Cache query

      // Decode
      val memAddr    = WireDefault(io.iCacheAccessPort.req.client.addr)
      val tag        = WireDefault(tagFromMemAddr(memAddr))
      val queryIndex = WireDefault(queryIndexFromMemAddr(memAddr))
      val byteOffset = WireDefault(byteOffsetFromMemAddr(memAddr))
      val dataIndex  = WireDefault(dataIndexFromByteOffset(byteOffset))

      // Read status-tag
      statusTagRams.foreach { ram =>
        ram.io.readPort.addr := queryIndex
      }
      val statusTagLines = Wire(Vec(Param.Count.ICache.setLen, new ICacheStatusTagBundle))
      statusTagLines.zip(statusTagRams.map(_.io.readPort.data)).foreach {
        case (line, data) =>
          line.isValid := data(ICacheStatusTagBundle.width - 1)
          line.tag     := data(ICacheStatusTagBundle.width - 2, 0)
      }

      // Read data (for read and write)
      dataLineRams.foreach { ram =>
        ram.io.readPort.addr := queryIndex
      }
      val dataLines = WireDefault(VecInit(dataLineRams.map(_.io.readPort.data)))

      // Calculate if hit and select
      val isSelectedVec    = WireDefault(VecInit(statusTagLines.map(line => line.isValid && (line.tag === tag))))
      val setIndex         = WireDefault(OHToUInt(isSelectedVec))
      val selectedDataLine = WireDefault(toDataLine(dataLines(setIndex)))
      val isCacheHit       = WireDefault(isSelectedVec.reduce(_ || _))

      // Save data for later use
      lastReg.memAddr        := memAddr
      lastReg.statusTagLines := statusTagLines
      lastReg.setIndex       := setIndex
      lastReg.dataLine       := selectedDataLine

      // Select data by data index from byte offset
      val selectedData = WireDefault(selectedDataLine(dataIndex))

      when(io.iCacheAccessPort.req.client.isValid) {
        when(isCacheHit) {
          // Cache hit
          // Remember to use regs
          isReadValidReg := true.B
          readDataReg    := selectedData

          // Next Stage 1
          nextState := State.ready
        }.otherwise {
          // Cache miss
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
          val statusTag  = Wire(new ICacheStatusTagBundle)
          statusTag.isValid := true.B
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
          io.iCacheAccessPort.res.isComplete := true.B
          io.iCacheAccessPort.res.isFailed   := axiMaster.io.read.res.isFailed
          io.iCacheAccessPort.res.read.data  := readData

          // Next Stage 1
          nextState := State.ready
        }
      }
    }
  }
}
