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
import pipeline.dispatch.bundles.IssueInfoWithValidBundle

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

  // val instInfosReg = Reg(Vec(issueNum, new InstInfoNdPort))
  // instInfosReg.foreach(InstInfoNdPort.setDefault(_))
  val instInfosReg = RegInit(VecInit(Seq.fill(issueNum)(InstInfoNdPort.default)))
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
    io.instInfoPorts.foreach(_ := InstInfoNdPort.default)
  }

  // val nextStates = WireDefault(VecInit(Seq.fill(issueNum)(State.nonBlocking)))
  // val statesReg  = RegInit(VecInit(Seq.fill(issueNum)(State.nonBlocking)))

  /** Combine stage 1 Get fetch infos
    *
    * TODO: 赋值reg
    */

  val fetchInfos         = WireDefault(VecInit(Seq.fill(issueNum)(IssueInfoWithValidBundle.default)))
  val fetchInfosStoreReg = RegInit(VecInit(Seq.fill(issueNum)(IssueInfoWithValidBundle.default)))

  fetchInfos
    .lazyZip(io.fetchInstDecodePorts)
    .foreach((dst, src) => {
      dst.valid     := src.valid
      dst.instInfo  := src.bits.instInfo
      dst.issueInfo := src.bits.instInfo
    })

  /** Combine stage 2 Select to issue
    */

  // 优先valid第0个
  val selectValidWires = Wire(
    Vec(
      issueNum,
      new IssueInfoWithValidBundle
    )
  )
  selectValidWires.foreach(_ := 0.U.asTypeOf(selectValidWires(0)))

  val canIssueMaxNum = io.pipelineControlPorts.map(_.stall.asUInt).reduce(_ +& _)

  /** valid inst --> issue
    */
  when(selectValidWires(0).valid) {
    when(!io.pipelineControlPorts(0).stall) {
      // select 0 -> issue 0
      instInfosReg(0)  := selectValidWires(0).instInfo
      issueInfosReg(0) := selectValidWires(0).issueInfo

      when(selectValidWires(1).valid && !io.pipelineControlPorts(1).stall) {
        // select 1 -> issue 1
        instInfosReg(1)  := selectValidWires(1).instInfo
        issueInfosReg(1) := selectValidWires(1).issueInfo
      }
    }.elsewhen(!io.pipelineControlPorts(1).stall) {
      // select 0 -> issue 1
      instInfosReg(1)  := selectValidWires(0).instInfo
      issueInfosReg(1) := selectValidWires(0).issueInfo
    }
  }

  // clear
  when(io.pipelineControlPorts.map(_.clear).reduce(_ || _)) {
    instInfosReg.foreach(_ := InstInfoNdPort.default)
    issueInfosReg.foreach(_ := IssuedInfoNdPort.default)
  }
  // flush all regs
  when(io.pipelineControlPorts.map(_.flush).reduce(_ || _)) {
    instInfosReg.foreach(_ := InstInfoNdPort.default)
    issueInfosReg.foreach(_ := IssuedInfoNdPort.default)
    // statesReg.foreach(_ := State.nonBlocking)
  }

}
