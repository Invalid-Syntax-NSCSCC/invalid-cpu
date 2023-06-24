package pipeline.dispatch

import chisel3._
import chisel3.util._
import control.bundles.CsrReadPort
import pipeline.common.{MultiBaseStageWOSaveIn, MultiQueue}
import pipeline.dispatch.bundles.{ReservationStationBundle, ScoreboardChangeNdPort}
import pipeline.dispatch.enums.ScoreboardState
import pipeline.execution.ExeNdPort
import pipeline.rob.bundles.{InstWbNdPort, RobReadRequestNdPort, RobReadResultNdPort}
import pipeline.rob.enums.RobDistributeSel
import spec.Param.{csrIssuePipelineIndex, loadStoreIssuePipelineIndex}
import spec._

// class FetchInstDecodeNdPort extends Bundle {
//   val decode   = new DecodeOutNdPort
//   val instInfo = new InstInfoNdPort
// }

// object FetchInstDecodeNdPort {
//   def default = (new FetchInstDecodeNdPort).Lit(
//     _.decode -> DecodeOutNdPort.default,
//     _.instInfo -> InstInfoNdPort.default
//   )
// }

class IssueStagePeerPort(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Bundle {

  // `IssueStage` <-> `Rob`
  val robEmptyNum = Input(UInt(log2Ceil(Param.Width.Rob._length + 1).W))
  val requests    = Output(Vec(issueNum, new RobReadRequestNdPort))
  val results     = Input(Vec(issueNum, new RobReadResultNdPort))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(pipelineNum, new InstWbNdPort))

  // `IssueStage` <-> `Scoreboard(csr)`
  val csrOccupyPort = Output(new ScoreboardChangeNdPort)
  val csrcore       = Input(ScoreboardState())
  val csrReadPort   = Flipped(new CsrReadPort)

  // `Cu` -> `IssueStage`
  val branchFlush = Input(Bool())

  // `Rob` -> `IssueStage`
  val tlbStart = Input(Bool())
  // Tlb ? -> `IssueStage`
  val tlbEnd = Input(Bool())
}

// dispatch & Reservation Stations
class IssueStage(
  issueNum:          Int = Param.issueInstInfoMaxNum,
  pipelineNum:       Int = Param.pipelineNum,
  reservationLength: Int = Param.reservationStationDeep)
    extends MultiBaseStageWOSaveIn(
      new FetchInstDecodeNdPort,
      new ExeNdPort,
      FetchInstDecodeNdPort.default,
      Some(new IssueStagePeerPort),
      issueNum,
      pipelineNum
    ) {

  // Fallback
  resultOutsReg.foreach(_.bits := 0.U.asTypeOf(resultOutsReg(0).bits))
  io.peer.get.csrOccupyPort := ScoreboardChangeNdPort.default
  io.peer.get.requests.foreach(_ := RobReadRequestNdPort.default)
  io.peer.get.csrReadPort.en   := false.B
  io.peer.get.csrReadPort.addr := false.B

  val reservationStations = Seq.fill(pipelineNum)(
    Module(
      new MultiQueue(
        reservationLength,
        1,
        1,
        new ReservationStationBundle,
        ReservationStationBundle.default,
        writeFirst = false
      )
    )
  )

  // fall back
  reservationStations.foreach { port =>
    port.io.enqueuePorts(0).valid := false.B
    port.io.enqueuePorts(0).bits  := DontCare
  }
  reservationStations.foreach(_.io.dequeuePorts(0).ready := false.B)
  reservationStations.foreach(_.io.isFlush := io.isFlush)
  reservationStations.foreach { rs =>
    rs.io.setPorts.zip(rs.io.elems).foreach {
      case (dst, src) =>
        dst.valid := false.B
        dst.bits  := src
    }
  }

  // stop when tlb running
  val fetchEnableByTlbFlag = RegInit(true.B)
  when(io.peer.get.tlbStart) {
    fetchEnableByTlbFlag := false.B
  }.elsewhen(io.peer.get.tlbEnd) {
    fetchEnableByTlbFlag := true.B
  }

  // stop fetch when branch
  val fetchEnableFlag = RegInit(true.B)

  /** fetch -> reservation stations
    */

  val readyReservations = WireDefault(VecInit(reservationStations.map(_.io.enqueuePorts(0).ready)))

  val dispatchMap  = WireDefault(VecInit(Seq.fill(issueNum)(VecInit(Seq.fill(pipelineNum)(false.B)))))
  val canDispatchs = WireDefault(VecInit(dispatchMap.map(_.reduceTree(_ || _))))

  io.ins.lazyZip(canDispatchs).foreach {
    case (in, canDispatch) =>
      in.ready := canDispatch && fetchEnableFlag && fetchEnableByTlbFlag
  }

  selectedIns.lazyZip(dispatchMap).zipWithIndex.foreach {
    case ((in, dispatchSel), src_idx) =>
      when(in.instInfo.isValid) {
        // select all dsts to dispatch
        val dstReady = Wire(Vec(pipelineNum, Bool()))
        dstReady.lazyZip(readyReservations).zipWithIndex.foreach {
          case ((dispatchEn, ready), dst_idx) =>
            //  Map condition src -> dst
            //     1. dst ready
            //     2. dst isn't occupied by previous srcs
            //     3. previous srcs dispatch success
            //     4. rob has empty position
            //     5. in the right pipeline index
            dispatchEn := ready &&
              !dispatchMap.take(src_idx).map(_(dst_idx)).foldLeft(false.B)(_ || _) &&
              canDispatchs.take(src_idx).foldLeft(true.B)(_ && _) &&
              (src_idx.U < io.peer.get.robEmptyNum)
            if (dst_idx == loadStoreIssuePipelineIndex) {
              when(in.decode.info.exeSel =/= ExeInst.Sel.loadStore) {
                dispatchEn := false.B
              }
            } else {
              when(in.decode.info.exeSel === ExeInst.Sel.loadStore) {
                dispatchEn := false.B
              }
            }
            if (dst_idx == csrIssuePipelineIndex) {
              when(in.instInfo.needCsr && (io.peer.get.csrcore =/= ScoreboardState.free)) {
                dispatchEn := false.B
              }
            } else {
              when(in.instInfo.needCsr) {
                dispatchEn := false.B
              }
            }
            if (dst_idx != Param.jumpBranchPipelineIndex) {
              when(in.decode.info.exeSel === ExeInst.Sel.jumpBranch) {
                dispatchEn := false.B
              }
            }
        }
        // select one
        dispatchSel.zip(PriorityEncoderOH(dstReady)).foreach {
          case (dst, src) =>
            dst := src
        }
      }
  }

  // -> reservation stations
  for (src_idx <- Seq.range(issueNum - 1, -1, -1)) {
    for (dst_idx <- 0 until pipelineNum) {
      when(dispatchMap(src_idx)(dst_idx) && fetchEnableFlag && fetchEnableByTlbFlag) {
        // decode info
        reservationStations(dst_idx).io
          .enqueuePorts(0)
          .valid := true.B
        reservationStations(dst_idx).io.enqueuePorts(0).bits.regReadPort.preExeInstInfo := selectedIns(
          src_idx
        ).decode.info
        reservationStations(dst_idx).io.enqueuePorts(0).bits.regReadPort.instInfo := selectedIns(src_idx).instInfo

        // rob result
        io.peer.get.requests(src_idx).en := true.B
        io.peer.get.requests(src_idx).readRequests.zip(selectedIns(src_idx).decode.info.gprReadPorts).foreach {
          case (req, decodeRead) =>
            req := decodeRead
        }
        io.peer.get.requests(src_idx).writeRequest                     := selectedIns(src_idx).decode.info.gprWritePort
        reservationStations(dst_idx).io.enqueuePorts(0).bits.robResult := io.peer.get.results(src_idx)

        // commit result
        io.peer.get.writebacks.foreach { writeback =>
          selectedIns(src_idx).decode.info.gprReadPorts
            .lazyZip(io.peer.get.results(src_idx).readResults)
            .lazyZip(reservationStations(dst_idx).io.enqueuePorts(0).bits.robResult.readResults)
            .foreach {
              case (readPort, robResult, rs) =>
                // may be error
                when(
                  writeback.en && readPort.en && robResult.sel === RobDistributeSel.robId && writeback.robId === robResult.result
                ) {
                  rs.sel    := RobDistributeSel.realData
                  rs.result := writeback.data
                }
            }
        }

        // imm
        when(selectedIns(src_idx).decode.info.isHasImm) {
          reservationStations(dst_idx).io.enqueuePorts(0).bits.robResult.readResults(1).sel := RobDistributeSel.realData
          reservationStations(dst_idx).io.enqueuePorts(0).bits.robResult.readResults(1).result := selectedIns(
            src_idx
          ).decode.info.imm
        }

        // csr score board
        if (dst_idx == Param.csrIssuePipelineIndex) {
          io.peer.get.csrOccupyPort.en   := selectedIns(src_idx).decode.info.needCsr
          io.peer.get.csrOccupyPort.addr := DontCare
        }

      }
    }
  }

  /** commit --fill--> reservation stations
    */

  reservationStations.foreach { reservationStation =>
    reservationStation.io.elems
      .lazyZip(reservationStation.io.elemValids)
      .lazyZip(reservationStation.io.setPorts)
      .foreach {
        case (elem, elemValid, set) =>
          when(elemValid) {
            io.peer.get.writebacks.foreach { wb =>
              elem.robResult.readResults.zip(set.bits.robResult.readResults).foreach {
                case (readResult, setReadResult) =>
                  when(readResult.sel === RobDistributeSel.robId && wb.en && readResult.result === wb.robId) {
                    set.valid            := true.B
                    setReadResult.sel    := RobDistributeSel.realData
                    setReadResult.result := wb.data
                  }
              }
            }
          }
      }
  }

  /** output
    */
  reservationStations.lazyZip(resultOutsReg).lazyZip(validToOuts).zipWithIndex.foreach {
    case ((reservationStation, out, outEnable), idx) =>
      val deqPort = reservationStation.io.dequeuePorts(0)
      val deqEn = outEnable &&
        deqPort.valid &&
        deqPort.bits.robResult.readResults
          .map(_.sel === RobDistributeSel.realData)
          .reduce(_ && _)
      out.valid     := deqEn
      deqPort.ready := deqEn

      out.bits.leftOperand  := deqPort.bits.robResult.readResults(0).result
      out.bits.rightOperand := deqPort.bits.robResult.readResults(1).result
      out.bits.exeSel       := deqPort.bits.regReadPort.preExeInstInfo.exeSel
      out.bits.exeOp        := deqPort.bits.regReadPort.preExeInstInfo.exeOp
      out.bits.gprWritePort := deqPort.bits.regReadPort.preExeInstInfo.gprWritePort
      // jumbBranch / memLoadStort / csr
      out.bits.jumpBranchAddr := deqPort.bits.regReadPort.preExeInstInfo.jumpBranchAddr
      if (idx == Param.csrIssuePipelineIndex) {
        io.peer.get.csrReadPort.en   := deqPort.bits.regReadPort.preExeInstInfo.csrReadEn
        io.peer.get.csrReadPort.addr := deqPort.bits.regReadPort.preExeInstInfo.csrAddr
        when(deqPort.bits.regReadPort.preExeInstInfo.csrReadEn) {
          out.bits.csrData := io.peer.get.csrReadPort.data
        }
      }

      out.bits.instInfo       := deqPort.bits.regReadPort.instInfo
      out.bits.instInfo.robId := deqPort.bits.robResult.robId
  }

  when(io.peer.get.branchFlush) {
    io.peer.get.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
    fetchEnableFlag := false.B
  }

  when(io.isFlush) {
    io.peer.get.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
    fetchEnableFlag := true.B
  }
}
