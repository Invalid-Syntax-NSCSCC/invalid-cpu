package spec

import chisel3._

object Param {
  // Configurable self-defined parameters go here

  val instQueueLength     = 5
  val regFileReadNum      = 3
  val scoreboardChangeNum = 1
  val instRegReadNum      = 2

  object Width {
    val exeSel = 3.W
    val exeOp  = 8.W
  }

  object Count {
    object Axi {
      val master = 1
      val slave = 3
    }
  }
}
