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

  val decoders     = Seq(new Decoder_2RI12)
  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))
  decoderWires.zip(decoders).foreach {
    case (port, decoder) => port := decoder.io.out
  }

  val isInstValid = WireDefault(decoderWires.map(_.isMatched).reduce(_ || _))
  val isNeedIssue = WireDefault((isInstAvailable || !isInstFetch) && isInstValid)
  val decoderIndex = WireDefault(OHToUInt(Cat(decoderWires.map(_.isMatched).reverse)))

  // TODO: Issue `nop` if not `isNeedIssue`

  when(isNeedIssue) {

  }
}
