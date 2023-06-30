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

class IssueReqStagePeerPort(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Bundle {

  // `IssueStage` <-> `Rob`
  val robEmptyNum = Input(UInt(log2Ceil(Param.Width.Rob._length + 1).W))
  val requests    = Output(Vec(issueNum, new RobReadRequestNdPort))

  // `Cu` -> `IssueStage`
  val branchFlush = Input(Bool())

  val plv = Input(UInt(2.W))
}

class IssueReqStage(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends MultiBaseStageWOSaveIn(
      new FetchInstDecodeNdPort,
      new DispatchNdPort,
      FetchInstDecodeNdPort.default,
      Some(new IssueStagePeerPort),
      issueNum,
      1
    ) {}
