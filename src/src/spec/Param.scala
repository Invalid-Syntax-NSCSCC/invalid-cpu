package spec

import chisel3._

object Param {
  // Configurable self-defined parameters go here

  val instQueueLength = 5
  val regFileReadNum  = 3

  object Width {
    val exeSel = 3.W
    val exeOp  = 8.W
  }
}
