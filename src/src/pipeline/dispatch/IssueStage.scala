package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.{DecodeOutNdPort, DecodePort, InstInfoBundle, IssuedInfoNdPort, ScoreboardChangeNdPort}
import pipeline.dispatch.decode.{Decoder, Decoder_2RI12}
import spec._

class IssueStage(scoreChangeNum: Int = Param.scoreboardChangeNum) extends Module {
  val io = IO(new Bundle {
    val fetchInstInfoPort = Flipped(Decoupled(new InstInfoBundle))

    // Scoreboard
    val occupyPorts = Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort))
    val regScores   = Input(Vec(Count.reg, Bool()))

    // `IssueStage` -> `RegReadStage` (next clock pulse)
    val issuedInfoPort = Output(new IssuedInfoNdPort)
  })

  // Pass to the next stage in a sequential way
  val issuedInfoReg = RegInit(IssuedInfoNdPort.default)
  io.issuedInfoPort := issuedInfoReg

  // Get next instruction if needed
  val isNonBlocking   = WireDefault(true.B)
  val isInstAvailable = WireDefault(io.fetchInstInfoPort.valid && isNonBlocking)
  val instInfo        = RegEnable(io.fetchInstInfoPort.bits, InstInfoBundle.default, isInstAvailable)
  io.fetchInstInfoPort.ready := isNonBlocking

  // Select a decoder
  val decoders = Seq(Module(new Decoder_2RI12))
  decoders.foreach(_.io.inst := instInfo.inst)
  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))
  decoderWires.zip(decoders).foreach {
    case (port, decoder) =>
      port := decoder.io.out
  }
  val decoderIndex    = WireDefault(OHToUInt(Cat(decoderWires.map(_.isMatched).reverse)))
  val selectedDecoder = WireDefault(decoderWires(decoderIndex))

  // Check scoreboard
  isNonBlocking := selectedDecoder.info.gprReadPorts.map { port =>
    !(port.en && io.regScores(port.addr))
  }.reduce(_ || _)

  val isInstValid = WireDefault(decoderWires.map(_.isMatched).reduce(_ || _))
  val isNeedIssue = WireDefault((isInstAvailable || !isNonBlocking) && isInstValid)

  // Fallback for no operation
  issuedInfoReg.isValid              := false.B
  issuedInfoReg.info                 := DontCare
  issuedInfoReg.info.gprWritePort.en := false.B
  io.occupyPorts.foreach { port =>
    port.en   := false.B
    port.addr := DontCare
  }

  // Issue pre-execution instruction
  when(isNeedIssue) {
    // Check scoreboard to eliminate data hazards
    issuedInfoReg.isValid := selectedDecoder.info.gprReadPorts
      .map(port => !(io.regScores(port.addr) && port.en))
      .reduce(_ && _) && (!(io.regScores(
      selectedDecoder.info.gprWritePort.addr
    ) && selectedDecoder.info.gprWritePort.en))

    // Indicate the occupation in scoreboard
    io.occupyPorts.zip(Seq(selectedDecoder.info.gprWritePort)).foreach {
      case (occupyPort, accessInfo) =>
        occupyPort.en   := accessInfo.en
        occupyPort.addr := accessInfo.addr
    }

    issuedInfoReg.info := selectedDecoder.info
  }
}
