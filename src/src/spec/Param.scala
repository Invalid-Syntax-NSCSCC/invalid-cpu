package spec

import chisel3._
import chisel3.experimental.ChiselEnum

object Param {
  // Configurable self-defined parameters go here

  val instQueueLength     = 5
  val regFileReadNum      = 3
  val scoreboardChangeNum = 1
  val instRegReadNum      = 2

  object Width {
    val exeSel                = 3.W
    val exeOp                 = 8.W
    val simpleFetchStageState = 2.W
  }

  object SimpleFetchStageState extends ChiselEnum {
    val idle, requestInst, waitInst = Value
  }
}
