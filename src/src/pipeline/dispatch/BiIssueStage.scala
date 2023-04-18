package pipeline.dispatch

import chisel3._
import chisel3.util._
import spec._
import control.bundles.PipelineControlNDPort
import pipeline.dispatch.enums.{IssueStageState => State}
import pipeline.writeback.bundles.InstInfoNdPort
import CsrRegs.ExceptionIndex
import common.bundles.PassThroughPort
import pipeline.dispatch.bundles.DecodeOutNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.bundles.IssuedInfoNdPort

class BiIssueStage(
  issueNum:       Int = 2,
  scoreChangeNum: Int = Param.regFileWriteNum)
    extends Module {
  val io = IO(new Bundle {
    // `InstQueue` -> `IssueStage`
    val fetchInstDecodePorts = Vec(
      issueNum,
      Decoupled(new Bundle {
        val decode   = new DecodeOutNdPort
        val instInfo = new InstInfoNdPort
      })
    )

    // `IssueStage` <-> `Scoreboard`
    val occupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val regScoress   = Vec(issueNum, Input(Vec(Count.reg, Bool())))

    // `IssueStage` <-> `Scoreboard(csr)`
    val csrOccupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val csrRegScoress   = Vec(issueNum, Input(Vec(Count.csrReg, Bool())))

    // `IssueStage` -> `RegReadStage` (next clock pulse)
    val issuedInfoPorts = Vec(issueNum, Output(new IssuedInfoNdPort))
    val instInfoPorts   = Vec(issueNum, Output(new InstInfoNdPort))
    // val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

    // pipeline control signal
    // `Cu` -> `IssueStage`
    val pipelineControlPort = Vec(issueNum, Input(new PipelineControlNDPort))
  })

  val instInfosReg = Reg(Vec(issueNum, new InstInfoNdPort))
  instInfosReg.foreach(InstInfoNdPort.setDefault(_))
  io.instInfoPorts := instInfosReg

  val issueInfosReg = RegInit(VecInit(Seq.fill(issueNum)(IssuedInfoNdPort.default)))
  io.issuedInfoPorts := issueInfosReg

  /** */

}
