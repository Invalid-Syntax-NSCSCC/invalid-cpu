package pipeline.simple

import chisel3._
import spec._
import chisel3.util.Decoupled
import chisel3.experimental.Param
import pipeline.common.bundles.FetchInstInfoBundle

class InstTranslateStage extends Module {

  val io = IO(new Bundle {
    val ins = Vec(
      Param.issueInstInfoMaxNum,
      Decoupled(Decoupled(new FetchInstInfoBundle))
    )
    val outs = Vec(
      Param.issueInstInfoMaxNum,
      Decoupled(new FetchInstInfoBundle)
    )
    val isFlush = Input(Bool())
  })

  if (Param.hasCustomInstruction) {
    io.ins.zip(io.outs).foreach {
      case (in, out) =>
        in <> out
    }
  } else {
    val seq = Seq()
  }
}
