package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.{DecodeOutNdPort, DecodePort, InstInfoBundle, IssuedInfoNdPort, ScoreboardChangeNdPort}
import pipeline.dispatch.decode.{Decoder, Decoder_2RI12}
import spec._

class IssueStage extends Module {
  val io = IO(new Bundle {
    val fetchInstInfoPort = Flipped(Decoupled(new InstInfoBundle))

    // Scoreboard
    val occupyPort = Output(new ScoreboardChangeNdPort)
    val regScores  = Input(Vec(Count.reg, Bool()))

    // `IssueStage` -> `RegReadStage`
    val issuedInfoPort = Output(new IssuedInfoNdPort)
  })

  // Get next instruction if needed
  val isInstFetch     = WireDefault(true.B)
  val isInstAvailable = WireDefault(io.fetchInstInfoPort.valid && isInstFetch)
  val instInfo        = RegEnable(io.fetchInstInfoPort.bits, InstInfoBundle.default, isInstAvailable)
  io.fetchInstInfoPort.ready := isInstFetch

  // Select a decoder
  val decoders = Seq(Module(new Decoder_2RI12))
  decoders.foreach(_.io.inst := instInfo.inst)
  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))
  decoderWires.zip(decoders).foreach {
    case (port, decoder) => port := decoder.io.out
  }
  val decoderIndex    = WireDefault(OHToUInt(Cat(decoderWires.map(_.isMatched).reverse)))
  val selectedDecoder = WireDefault(decoderWires(decoderIndex))

  val isInstValid = WireDefault(decoderWires.map(_.isMatched).reduce(_ || _))
  val isNeedIssue = WireDefault((isInstAvailable || !isInstFetch) && isInstValid)

  // Fallback for no operation
  io.issuedInfoPort.isValid := false.B
  io.issuedInfoPort.info    := DontCare

  // Issue pre-microcode
  when(isNeedIssue) {
    // Check scoreboard to eliminate data hazards
    io.issuedInfoPort.isValid := selectedDecoder.info.gprReadPorts
      .map(port => !(io.regScores(port.addr) && port.en))
      .reduce(_ && _) && (!(io.regScores(
      selectedDecoder.info.gprWritePort.addr
    ) && selectedDecoder.info.gprWritePort.en))

    io.issuedInfoPort.info := selectedDecoder.info
  }
}
