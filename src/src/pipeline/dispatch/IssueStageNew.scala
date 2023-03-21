package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.{DecodeOutNdPort, DecodePort, InstInfoBundle, IssuedInfoNdPort, ScoreboardChangeNdPort}
import pipeline.dispatch.decode.{Decoder, Decoder_2R, Decoder_2RI12, Decoder_2RI16, Decoder_3R, Decoder_4R}
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort

class IssueStageNew(
  scoreChangeNum: Int = Param.scoreboardChangeNum,
  instInfoMaxNum: Int = Param.issueInstInfoMaxNum,
  dispatchNum:    Int = Param.dispatchInstNum)
    extends Module {
  val io = IO(new Bundle {
    // `InstQueue` -> `IssueStage`
    val fetchInstInfoPort = Flipped(Decoupled(new InstInfoBundle))

    // `IssueStage` <-> `Scoreboard`
    val occupyPorts = Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort))
    val regScores   = Input(Vec(Count.reg, Bool()))

    // `IssueStage` -> `RegReadStage` (next clock pulse)
    val issuedInfoPort = Output(new IssuedInfoNdPort)

    // pipeline control signal
    // `CtrlStage` -> `IssueStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
  })

  val stallFromCtrl = WireDefault(io.pipelineControlPort.stall)

  /** ******************************************** Decoder
    */
  val decoders = Seq(
    Module(new Decoder_2RI12),
    Module(new Decoder_2RI16),
    Module(new Decoder_2R),
    Module(new Decoder_3R),
    Module(new Decoder_4R)
  )
  decoders.foreach(_.io.instInfoPort := io.fetchInstInfoPort.bits)

  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))
  decoderWires.zip(decoders).foreach {
    case (port, decoder) =>
      port := decoder.io.out
  }
  val decoderIndex    = WireDefault(OHToUInt(Cat(decoderWires.map(_.isMatched).reverse)))
  val selectedDecoder = WireDefault(decoderWires(decoderIndex))

  val isInstValid = WireDefault(decoderWires.map(_.isMatched).reduce(_ || _))

  /** ******************************************** Inst Pool
    */

  val IssueQueue = VecInit(Seq.fill(instInfoMaxNum)(new InstInfoBundle))

  val issuedInfoReg = RegInit(IssuedInfoNdPort.default)

  /** ******************************************** State
    */
  val sNonNon :: sInNon :: sNonOut :: sInOut :: Nil = Enum(4)
  val state                                         = RegInit(sNonNon)

  state := sInOut
  when(io.pipelineControlPort.stall) {
    state := sNonNon
  }.elsewhen(io.fetchInstInfoPort.valid) {
    state := sNonNon
  }

  /** ******************************************** Check scoreboard
    */
  val scoreboardBlocking = WireDefault(
    selectedDecoder.info.gprReadPorts.map { port =>
      (port.en && io.regScores(port.addr))
    }.reduce(_ || _)
  )

  val enIssue   = WireDefault(!scoreboardBlocking && !stallFromCtrl)
  val enGetInst = WireDefault(isInstValid && !enIssue)

}
