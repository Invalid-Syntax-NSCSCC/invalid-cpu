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
  issueNum:       Int = Param.issueInstInfoMaxNum,
  pipelineNum:    Int = Param.pipelineNum,
  scoreChangeNum: Int = Param.regFileWriteNum)
    extends Bundle {

  // `IssueStage` <-> `Rob`
  val requests = Output(Vec(issueNum, new RobReadRequestNdPort))
  val results  = Input(Vec(issueNum, new RobReadResultNdPort))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(pipelineNum, new InstWbNdPort))

  // `IssueStage` <-> `Scoreboard(csr)`
  val csrOccupyPort = Output(new ScoreboardChangeNdPort)
  val csrRegScore   = Input(ScoreboardState())
}

// dispatch & Reservation Stations
class IssueStage
    extends MultiBaseStage(
      new FetchInstDecodeNdPort,
      new ExeNdPort,
      FetchInstDecodeNdPort.default,
      Some(new IssueStagePeerPort)
    ) {

  // Fallback
  resultOutsReg.foreach(_.bits := 0.U.asTypeOf(resultOutsReg(0).bits))
  io.peer.get.csrOccupyPort := ScoreboardChangeNdPort.default
  io.peer.get.requests.foreach(_ := RobReadRequestNdPort.default)

}
