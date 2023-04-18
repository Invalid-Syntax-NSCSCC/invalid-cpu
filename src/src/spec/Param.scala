package spec

import chisel3._
import chisel3.util._
import spec.PipelineStageIndex
import chisel3.ChiselEnum

object Param {
  // Configurable self-defined parameters go here

  val isDiffTest = true

  val instQueueLength        = 5
  val regFileReadNum         = 2
  val regFileWriteNum        = 1
  val scoreboardChangeNum    = 1 // 3
  val csrScoreBoardChangeNum = 1
  val instRegReadNum         = 2
  val ctrlControlNum         = PipelineStageIndex.getCount
  val issueInstInfoMaxNum    = 1
  val dispatchInstNum        = 1 // 发射数量
  val csrRegsReadNum         = 1
  val csrRegsWriteNum        = 1

  val dataForwardInputNum = 2

  object Width {
    val exeSel                = 3.W
    val exeOp                 = 8.W
    val simpleFetchStageState = 2.W

    object Axi { // crossbar
      val slaveId  = 8
      val masterId = slaveId + log2Ceil(Count.Axi.slave)
    }

    object DCache {
      val _addr       = 6 // TODO: Choose an optimal value
      val _byteOffset = log2Ceil(Count.DCache.dataPerLine) + log2Ceil(wordLength / byteLength)
      val _dataLine   = Count.DCache.dataPerLine * spec.Width.Mem._data
      val _tag        = spec.Width.Mem._addr - _addr - _byteOffset

      val addr       = _addr.W
      val byteOffset = _byteOffset.W
      val tag        = _tag.W
      val dataLine   = _dataLine.W
    }
  }

  object Count {
    object Axi { // crossbar
      val master = 1
      val slave  = 3
    }

    object DCache {
      val setLen      = 2 // Also the number of RAMs for data; TODO: Choose an optimal value
      val dataPerLine = 4 // TODO: One data line is 64 bytes
      val sizePerRam  = math.pow(2, Width.DCache._addr).toInt
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

    object Id {
      val iCache = 0
      val dCache = 1
    }
  }

  object SimpleFetchStageState extends ChiselEnum {
    val idle, requestInst, waitInst = Value
  }

  object ExeStageState extends ChiselEnum {
    val nonBlocking, blocking = Value
  }

  object AluState extends ChiselEnum {
    val nonBlocking, blocking = Value
  }
}
