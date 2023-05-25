package spec

import chisel3.util._
import chisel3.{ChiselEnum, _}

object Param {
  // Configurable self-defined parameters go here

  val isDiffTest = true

  val instQueueLength        = 5
  val regFileReadNum         = 2
  val regFileWriteNum        = 1
  val scoreboardChangeNum    = 1 // 3
  val csrScoreBoardChangeNum = 1
  val instRegReadNum         = 2
  val ctrlControlNum         = PipelineStageIndex.count + 1
  val issueInstInfoMaxNum    = 1
  val dispatchInstNum        = 1 // 发射数量
  val csrRegsReadNum         = 1
  val csrRegsWriteNum        = 1
  val robIdLength            = 32 // rob给出的id

  val dataForwardInputNum = 2

  object Width {
    val exeSel = log2Ceil(ExeInst.Sel.count + 1).W
    val exeOp  = log2Ceil(ExeInst.Op.count + 1).W

    object Axi {
      private val _data = 32

      val data = _data.W
      val strb = (_data / byteLength).W

      val awuser = 0.W
      val wuser  = 0.W
      val aruser = 0.W
      val ruser  = 0.W
      val buser  = 0.W

      val slaveId  = 2.W
      val masterId = 4.W
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

    object Tlb {
      val num      = 32
      val transNum = 2
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
