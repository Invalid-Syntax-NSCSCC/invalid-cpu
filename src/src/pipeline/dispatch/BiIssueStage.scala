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
import pipeline.dataforward.bundles.ReadPortWithValid

class BiIssueStage(
  issueNum:       Int = 2,
  scoreChangeNum: Int = Param.regFileWriteNum)
    extends Module {
  val io = IO(new Bundle {
    // `InstQueue` -> `IssueStage`
    val fetchInstDecodePorts = Vec(
      issueNum,
      Flipped(Decoupled(new Bundle {
        val decode   = new DecodeOutNdPort
        val instInfo = new InstInfoNdPort
      }))
    )

    // `IssueStage` <-> `RobStage`
    val idGetPorts = Vec(issueNum, Flipped(new ReadPortWithValid))

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
    val pipelineControlPorts = Vec(issueNum, Input(new PipelineControlNDPort))
  })

  val instInfosReg = Reg(Vec(issueNum, new InstInfoNdPort))
  instInfosReg.foreach(InstInfoNdPort.setDefault(_))
  io.instInfoPorts := instInfosReg

  val issueInfosReg = RegInit(VecInit(Seq.fill(issueNum)(IssuedInfoNdPort.default)))
  io.issuedInfoPorts := issueInfosReg

  // fall back
  if (true) {
    io.fetchInstDecodePorts.foreach { port =>
      port.ready := false.B
    }
    io.occupyPortss.foreach(_.foreach { port =>
      port.en   := false.B
      port.addr := zeroWord
    })
    io.csrOccupyPortss.foreach(_.foreach { port =>
      port.en   := false.B
      port.addr := zeroWord
    })
    io.issuedInfoPorts.foreach(_ := IssuedInfoNdPort.default)
    io.instInfoPorts.foreach(InstInfoNdPort.setDefault(_))
  }

  val nextStates = WireDefault(VecInit(Seq.fill(issueNum)(State.nonBlocking)))
  val statesReg  = RegInit(VecInit(Seq.fill(issueNum)(State.nonBlocking)))

  // 优先valid第0个
  val selectWires = Wire(
    Vec(
      issueNum,
      new Bundle {
        val valid     = Bool()
        val instInfo  = new InstInfoNdPort
        val issueInfo = new IssuedInfoNdPort
      }
    )
  )
  selectWires.foreach(_ := 0.U.asTypeOf(selectWires(0)))

  // select --> issue
  when(selectWires(0).valid) {
    when(!io.pipelineControlPorts(0).stall) {
      // select 0 -> issue 0
      instInfosReg(0)  := selectWires(0).instInfo
      issueInfosReg(0) := selectWires(0).issueInfo

      when(selectWires(1).valid && !io.pipelineControlPorts(1).stall) {
        // select 1 -> issue 1
        instInfosReg(1)  := selectWires(1).instInfo
        issueInfosReg(1) := selectWires(1).issueInfo
      }
    }.elsewhen(!io.pipelineControlPorts(1).stall) {
      // select 0 -> issue 1
      instInfosReg(1)  := selectWires(0).instInfo
      issueInfosReg(1) := selectWires(0).issueInfo
    }
  }

}
