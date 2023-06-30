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
import control.enums.ExceptionPos

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

class DispatchBundle extends Bundle {
  val valid         = Bool()
  val resultPortIdx = UInt(log2Ceil(Param.issueInstInfoMaxNum).W)
  val regReadPort   = new RegReadNdPort
}

class DispatchNdPort extends Bundle {
  val dispatchs = Vec(Param.pipelineNum, new DispatchBundle)
}

// dispatch & Reservation Stations
class NewIssueStage(
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

  // Request

  // stop fetch when branch
  val fetchEnableFlag = RegInit(true.B)

  val dispatchRegs = Reg(Vec(pipelineNum, new DispatchBundle))
  dispatchRegs.foreach { reg =>
    reg.valid         := false.B
    reg.resultPortIdx := DontCare
    reg.regReadPort   := DontCare
  }

  // select to dispatch

  // Result

  // fetch -> reservation stations
  val readyReservations = WireDefault(VecInit(reservationStations.map(_.io.enqueuePorts(0).ready)))

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
