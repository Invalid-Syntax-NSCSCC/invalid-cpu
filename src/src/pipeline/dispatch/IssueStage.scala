package pipeline.dispatch

import chisel3._
import chisel3.util._
import spec._
import chisel3.experimental.BundleLiterals._
import spec.Param.{csrIssuePipelineIndex, loadStoreIssuePipelineIndex}
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.queue.bundles.DecodeOutNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.enums.ScoreboardState
import pipeline.rob.bundles.RobReadRequestNdPort
import pipeline.rob.bundles.RobReadResultNdPort
import pipeline.common.MultiBaseStage
import pipeline.execution.ExeNdPort
import pipeline.dispatch.FetchInstDecodeNdPort
import pipeline.rob.bundles.InstWbNdPort
import pipeline.common.MultiQueue
import pipeline.dispatch.bundles.ReservationStationBundle
import pipeline.rob.enums.RobDistributeSel
import control.bundles.CsrReadPort
import pipeline.common.MultiBaseStageWOSaveIn

// class FetchInstDecodeNdPort extends Bundle {
//   val decode   = new DecodeOutNdPort
//   val instInfo = new InstInfoNdPort
// }

// object FetchInstDecodeNdPort {
//   val default = (new FetchInstDecodeNdPort).Lit(
//     _.decode -> DecodeOutNdPort.default,
//     _.instInfo -> InstInfoNdPort.default
//   )
// }

class IssueStagePeerPort(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Bundle {

  // `IssueStage` <-> `Rob`
  val robEmptyNum   = Input(UInt(Param.Width.Rob.id))
  val requests      = Output(Vec(issueNum, new RobReadRequestNdPort))
  val results       = Input(Vec(issueNum, new RobReadResultNdPort))
  val resultsValid  = Input(Bool())
  val robInstValids = Input(Vec(Param.Width.Rob._length, Bool()))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(pipelineNum, new InstWbNdPort))

  // `IssueStage` <-> `Scoreboard(csr)`
  val csrOccupyPort = Output(new ScoreboardChangeNdPort)
  val csrRegScore   = Input(ScoreboardState())
  val csrReadPort   = Flipped(new CsrReadPort)

  // branch flush
  val branchFlush = Input(Bool())
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
        ReservationStationBundle.default
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
  // reservationStations.foreach(_.io.setPorts.foreach { port =>
  //   port.valid := false.B
  //   port.bits  := DontCare
  // })

  /** fetch -> reservation stations
    */

  val readyReservations = WireDefault(VecInit(reservationStations.map(_.io.enqueuePorts(0).ready)))

  val dispatchMap  = WireDefault(VecInit(Seq.fill(issueNum)(VecInit(Seq.fill(pipelineNum)(false.B)))))
  val canDispatchs = WireDefault(VecInit(dispatchMap.map(_.reduceTree(_ || _))))

  io.ins.lazyZip(canDispatchs).foreach {
    case (in, canDispatch) =>
      in.ready := canDispatch && io.peer.get.resultsValid // * rob distribute failed
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
            if (dst_idx != loadStoreIssuePipelineIndex) {
              when(in.decode.info.exeSel === ExeInst.Sel.loadStore) {
                dispatchEn := false.B
              }
            }
            if (dst_idx != csrIssuePipelineIndex) {
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
      when(dispatchMap(src_idx)(dst_idx)) {
        // decode info
        reservationStations(dst_idx).io.enqueuePorts(0).valid := io.peer.get.resultsValid // * rob distribute failed
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
        io.peer.get.csrOccupyPort.en   := selectedIns(src_idx).decode.info.needCsr
        io.peer.get.csrOccupyPort.addr := DontCare
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
      val deqEn = outEnable && deqPort.valid && (deqPort.bits.robResult.readResults
        .map(_.sel === RobDistributeSel.realData)
        .reduce(_ && _) || !io.peer.get.robInstValids(deqPort.bits.robResult.robId))
      out.valid     := deqEn
      deqPort.ready := deqEn

      io.peer.get.csrReadPort.en   := deqPort.bits.regReadPort.preExeInstInfo.csrReadEn
      io.peer.get.csrReadPort.addr := DontCare

      out.bits.leftOperand  := deqPort.bits.robResult.readResults(0).result
      out.bits.rightOperand := deqPort.bits.robResult.readResults(1).result
      out.bits.exeSel       := deqPort.bits.regReadPort.preExeInstInfo.exeSel
      out.bits.exeOp        := deqPort.bits.regReadPort.preExeInstInfo.exeOp
      out.bits.gprWritePort := deqPort.bits.regReadPort.preExeInstInfo.gprWritePort
      // jumbBranch / memLoadStort / csr
      out.bits.jumpBranchAddr := deqPort.bits.regReadPort.preExeInstInfo.jumpBranchAddr
      when(deqPort.bits.regReadPort.preExeInstInfo.csrReadEn) {
        out.bits.csrData := io.peer.get.csrReadPort.data
      }

      out.bits.instInfo         := deqPort.bits.regReadPort.instInfo
      out.bits.instInfo.robId   := deqPort.bits.robResult.robId
      out.bits.instInfo.isValid := io.peer.get.robInstValids(deqPort.bits.robResult.robId)

  }

  when(io.peer.get.branchFlush) {
    io.peer.get.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
  }

  when(io.isFlush) {
    io.peer.get.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
  }
}
