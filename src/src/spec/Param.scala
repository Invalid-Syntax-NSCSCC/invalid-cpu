package spec

import chisel3._
import chisel3.util._
import spec.PipelineStageIndex
import chisel3.experimental.ChiselEnum

object Param {
  // Configurable self-defined parameters go here

  val isDiffTest = true

  val instQueueLength     = 5
  val regFileReadNum      = 3
  val scoreboardChangeNum = 1
  val instRegReadNum      = 2
  val ctrlControlNum      = PipelineStageIndex.getCount
  val issueInstInfoMaxNum = 1
  val dispatchInstNum     = 1 // 发射数量
  val csrRegsWriteNum     = 1

  object Width {
    val exeSel                = 3.W
    val exeOp                 = 8.W
    val simpleFetchStageState = 2.W

    val memSel = 4.W

    object Axi { // crossbar
      val slaveId  = 8
      val masterId = slaveId + log2Ceil(Count.Axi.slave)
    }
  }

  object Count {
    object Axi { // crossbar
      val master = 1
      val slave  = 3
    }
  }

  object Axi {
    object Arb {
      val typeRoundRobin  = true // select round robin arbitration
      val block           = true // blocking arbiter enable
      val blockAck        = true // block on acknowledge assert when nonzero, request deassert when 0
      val lsbHighPriority = true // LSB priority selection
    }

    object Crossbar {
      val aruserEnable = false // propagate aruser signal
      val ruserEnable  = false // propagate ruser signal
      val awuserEnable = false
      val wuserEnable  = false
      val buserEnable  = false
    }
  }

  object SimpleFetchStageState extends ChiselEnum {
    val idle, requestInst, waitInst = Value
  }

  object IssueStageState extends ChiselEnum {
    val nonBlocking, blocking = Value
  }

  object ExeStageState extends ChiselEnum {
    val nonBlocking, blocking = Value
  }

  object AluState extends ChiselEnum {
    val nonBlocking, blocking = Value
  }
}
