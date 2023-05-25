package pipeline.dispatch

import chisel3._
import chisel3.util._
import spec._
import control.bundles.PipelineControlNdPort
import pipeline.dispatch.enums.{IssueStageState => State}
import pipeline.writeback.bundles.InstInfoNdPort
import Csr.ExceptionIndex
import common.bundles.PassThroughPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.bundles.IssuedInfoNdPort
import pipeline.dataforward.bundles.ReadPortWithValid
import pipeline.dispatch.bundles.IssueInfoWithValidBundle
import pipeline.queue.bundles.DecodeOutNdPort
import pipeline.rob.bundles.RobIdDistributePort

// TODO: deal WAR data hazard
class BiIssueStage(
  issueNum:       Int = 2,
  scoreChangeNum: Int = Param.regFileWriteNum,
  robIdLength:    Int = 32,
  robLengthLog:   Int = 4)
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

    val robEmptyNum = Input(UInt(robLengthLog.W))
    val idGetPorts  = Vec(issueNum, Flipped(new RobIdDistributePort(idLength = robIdLength)))

    // `IssueStage` <-> `Scoreboard`
    val occupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val regScores    = Input(Vec(Count.reg, Bool()))

    // `IssueStage` <-> `Scoreboard(csr)`
    val csrOccupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val csrRegScores    = Input(Vec(Count.csrReg, Bool()))

    // `IssueStage` -> `RegReadStage` (next clock pulse)
    val issuedInfoPorts = Vec(issueNum, Decoupled(new IssuedInfoNdPort))

    // pipeline control signal
    // `Cu` -> `IssueStage`
    val pipelineControlPorts = Vec(issueNum, Input(new PipelineControlNdPort))
  })

  val issueEnablesReg = RegInit(VecInit(Seq.fill(issueNum)(false.B)))
  issueEnablesReg.foreach(_ := false.B)
  val issueInfosReg = RegInit(VecInit(Seq.fill(issueNum)(IssuedInfoNdPort.default)))
  io.issuedInfoPorts.lazyZip(issueInfosReg).lazyZip(issueEnablesReg).foreach{
    (dst, src, valid) => 
      dst.bits := issueInfosReg
      dst.valid := valid
  }

  // fall back
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

  /** Combine stage 1 : get fetch infos
    */

  val fetchInfos = WireDefault(VecInit(Seq.fill(issueNum)(IssueInfoWithValidBundle.default)))

  fetchInfos
    .lazyZip(io.fetchInstDecodePorts)
    .foreach((dst, src) => {
      dst.valid                    := src.valid
      dst.issueInfo.instInfo       := src.bits.instInfo
      dst.issueInfo.preExeInstInfo := src.bits.instInfo
    })

  /** Combine stage 2 : Select to issue ; decide input
    */

  // 优先valid第0个
  val selectValidWires = Wire(
    Vec(
      issueNum,
      new IssueInfoWithValidBundle
    )
  )
  selectValidWires.foreach(_ := 0.U.asTypeOf(selectValidWires(0)))

  // val canIssueMaxNumFromStall = WireDefault(io.pipelineControlPorts.map(_.stall.asUInt).reduce(_ +& _))
  val canIssueMaxNumFromPipeline = WireDefault(io.issuedInfoPorts.map(_.ready.asUInt).reduce(_ +& _))
  val canIssueMaxNumFromRob      = WireDefault(io.robEmptyNum)
  when(io.robEmptyNum === 1.U && !io.fetchInstDecodePorts.map(_.bits.decode.info.gprWritePort.en).reduce(_ && _)) {
    canIssueMaxNumFromRob := 2.U
  }
  when(io.robEmptyNum === 0.U && !io.fetchInstDecodePorts.map(_.bits.decode.info.gprWritePort.en).reduce(_ || _)) {
    canIssueMaxNumFromRob := 2.U
  }
  val canIssueMaxNum = Mux(
    canIssueMaxNumFromRob < canIssueMaxNumFromPipeline,
    canIssueMaxNumFromRob,
    canIssueMaxNumFromPipeline
  )

  val fetchCanIssue = WireDefault(VecInit(fetchInfos.map { fetchInfos =>
    fetchInfos.valid &&
    !((fetchInfos.issueInfo.preExeInstInfo.gprReadPorts
      .map({ readPort =>
        readPort.en && io.regScores(readPort.addr)
      }))
      .reduce(_ || _)) &&
    !(fetchInfos.issueInfo.preExeInstInfo.csrReadEn && io.csrRegScores(fetchInfos.issueInfo.preExeInstInfo.csrAddr))
  }))

  when(canIssueMaxNum.orR) {
    // 可发射至少一个

    when(fetchCanIssue(0)) {
      selectValidWires(0)              := fetchInfos(0)
      io.fetchInstDecodePorts(0).ready := true.B
      when(
        fetchCanIssue(1) &&
          !(
            fetchInfos(0).issueInfo.preExeInstInfo.gprWritePort.en &&
              fetchInfos(1).issueInfo.preExeInstInfo.gprReadPorts.map { readPort =>
                readPort.en && (readPort.addr === fetchInfos(0).issueInfo.preExeInstInfo.gprWritePort.addr)
              }.reduce(_ || _)
          )
      ) {
        selectValidWires(1)              := fetchInfos(1)
        io.fetchInstDecodePorts(1).ready := true.B
      }
    }
  }

  /** Combine stage 3 : valid inst --> issue ; connect score board
    */
  // default : no issue
  when(selectValidWires(0).valid) {
    when(io.issuedInfoPorts(0).ready) {
      // select 0 -> issue 0
      issueEnablesReg(0) := true.B

      issueInfosReg(0)      := selectValidWires(0).issueInfo
      io.occupyPortss(0)(0) := selectValidWires(0).issueInfo.preExeInstInfo.gprWritePort

      io.idGetPorts(0).writeEn                     := selectValidWires(0).issueInfo.preExeInstInfo.gprWritePort.en
      selectValidWires(0).issueInfo.instInfo.robId := io.idGetPorts(0).id

      when(selectValidWires(1).valid && io.issuedInfoPorts(1).ready) {
        // select 1 -> issue 1
        issueEnablesReg(1) := true.B

        issueInfosReg(1)      := selectValidWires(1).issueInfo
        io.occupyPortss(1)(0) := selectValidWires(1).issueInfo.preExeInstInfo.gprWritePort

        io.idGetPorts(1).writeEn                     := selectValidWires(1).issueInfo.preExeInstInfo.gprWritePort.en
        selectValidWires(1).issueInfo.instInfo.robId := io.idGetPorts(1).id
      }
    }.elsewhen(io.issuedInfoPorts(1).ready) {
      // select 0 -> issue 1
      issueEnablesReg(1) := true.B

      issueInfosReg(1)      := selectValidWires(0).issueInfo
      io.occupyPortss(0)(0) := selectValidWires(0).issueInfo.preExeInstInfo.gprWritePort

      io.idGetPorts(0).writeEn                     := selectValidWires(0).issueInfo.preExeInstInfo.gprWritePort.en
      selectValidWires(0).issueInfo.instInfo.robId := io.idGetPorts(0).id
    }
  }

  // flush all regs
  when(io.pipelineControlPorts.map(_.flush).reduce(_ || _)) {
    issueEnablesReg.foreach(_ := false.B)
    issueInfosReg.foreach(_ := IssuedInfoNdPort.default)
    io.fetchInstDecodePorts.foreach(_.ready := false.B)
    io.issuedInfoPorts.foreach(_.valid := false.B)
  }

}
