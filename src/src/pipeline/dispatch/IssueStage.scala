package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.InstInfoBundle
import spec._

class IssueStage extends Module {
  val io = IO(new Bundle {
    val instInfoPort = Input(new InstInfoBundle)
  })

}
