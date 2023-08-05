package pipeline.simple.id

import chisel3._
import chisel3.util._
import pipeline.common.bundles.FetchInstInfoBundle
import pipeline.simple.decode.bundles.DecodeOutNdPort
import pipeline.simple.decode._

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

  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))

  decoderWires.zip(decoders).foreach {
    case (port, decoder) =>
      port := decoder.io.out
  }

  val decoderIndex =
    OHToUInt(Cat(decoderWires.map(_.isMatched).reverse))

  io.out := decoderWires(decoderIndex)
}
