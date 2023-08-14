package pipeline.simple.id

import chisel3._
import pipeline.common.bundles.FetchInstInfoBundle
import pipeline.simple.bundles.PreExeInstNdPort
import pipeline.simple.decode._
import pipeline.simple.decode.bundles.DecodeOutNdPort
import utils.MultiMux1

class DecodeUnit extends Module {
  val io = IO(new Bundle {
    val in  = Input(new FetchInstInfoBundle)
    val out = Output(new DecodeOutNdPort)
  })

  val decoders =
    Seq(
      Module(new Decoder_2RI12),
      Module(new Decoder_2RI14),
      Module(new Decoder_2RI16),
      Module(new Decoder_2R),
      Module(new Decoder_3R),
      Module(new Decoder_special)
    )

  decoders.foreach(_.io.instInfoPort := io.in)

  val mux = Module(new MultiMux1(decoders.length, new PreExeInstNdPort, PreExeInstNdPort.default))
  mux.io.inputs.zip(decoders).foreach {
    case (dst, src) =>
      dst.valid := src.io.out.isMatched
      dst.bits  := src.io.out.info
  }

  io.out.isMatched := mux.io.output.valid
  io.out.info      := mux.io.output.bits
}
