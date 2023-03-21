package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.{DecodeOutNdPort, DecodePort, InstInfoBundle, IssuedInfoNdPort, ScoreboardChangeNdPort}
import pipeline.dispatch.decode.{Decoder, Decoder_2R, Decoder_2RI12, Decoder_2RI16, Decoder_3R, Decoder_4R}
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort

class IssueStage(scoreChangeNum: Int = Param.scoreboardChangeNum) extends Module {
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

  // TODO: Refactor using state machine

  // Pass to the next stage in a sequential way
  val issuedInfoReg = RegInit(IssuedInfoNdPort.default)
  io.issuedInfoPort := issuedInfoReg

  // Get next instruction if needed
  val isNonBlocking   = WireDefault(true.B)
  val isInstAvailable = WireDefault(io.fetchInstInfoPort.valid && isNonBlocking)
  val instInfoReg     = RegEnable(io.fetchInstInfoPort.bits, InstInfoBundle.default, isInstAvailable)
  io.fetchInstInfoPort.ready := isNonBlocking

  // Select a decoder
  val decoders = Seq(
    Module(new Decoder_2RI12),
    Module(new Decoder_2RI16),
    Module(new Decoder_2R),
    Module(new Decoder_3R),
    Module(new Decoder_4R)
  )
  decoders.foreach(_.io.inst := instInfoReg.inst)

  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))
  decoderWires.zip(decoders).foreach {
    case (port, decoder) =>
      port := decoder.io.out
  }
  val decoderIndex    = WireDefault(OHToUInt(Cat(decoderWires.map(_.isMatched).reverse)))
  val selectedDecoder = WireDefault(decoderWires(decoderIndex))

  // Check scoreboard
  val isScoreboardNonBlocking = WireDefault(selectedDecoder.info.gprReadPorts.map { port =>
    !(port.en && io.regScores(port.addr))
  }.reduce(_ && _))
  isNonBlocking := isScoreboardNonBlocking && !io.pipelineControlPort.stall

  val isInstValid = WireDefault(decoderWires.map(_.isMatched).reduce(_ || _))
  val isNeedIssue = WireDefault(isInstAvailable && isInstValid) // TODO: Include in refactor

  // Fallback for no operation
  issuedInfoReg.isValid              := isNeedIssue
  issuedInfoReg.info                 := DontCare
  issuedInfoReg.info.gprWritePort.en := false.B
  io.occupyPorts.foreach { port =>
    port.en   := false.B
    port.addr := DontCare
  }

  // Issue pre-execution instruction
  when(isNeedIssue) {
    // Indicate the occupation in scoreboard
    io.occupyPorts.zip(Seq(selectedDecoder.info.gprWritePort)).foreach {
      case (occupyPort, accessInfo) =>
        occupyPort.en   := accessInfo.en
        occupyPort.addr := accessInfo.addr
    }

    issuedInfoReg.info := selectedDecoder.info
  }
}
