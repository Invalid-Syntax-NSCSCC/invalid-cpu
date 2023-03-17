package spec

import chisel3._
import chisel3.util._

object Param {
  // Configurable self-defined parameters go here

  val instQueueLength     = 5
  val regFileReadNum      = 3
  val scoreboardChangeNum = 1
  val instRegReadNum      = 2

  object Width {
    val exeSel = 3.W
    val exeOp  = 8.W

    object Axi { // crossbar
      val slaveId = 8
      val masterId = slaveId + log2Ceil(Count.Axi.slave)
    }
  }

  object Count {
    object Axi { // crossbar
      val master = 1
      val slave = 3
    }
  }
}
