package spec

import chisel3.util._
import chisel3.{ChiselEnum, _}

object Param {
  // Configurable self-defined parameters go here

  val isDiffTest = true

  val instQueueLength        = 8
  val regFileReadNum         = 2
  val regFileWriteNum        = 1
  val scoreboardChangeNum    = 1 // 3
  val csrScoreBoardChangeNum = 1
  val instRegReadNum         = 2
  val ctrlControlNum         = PipelineStageIndex.count + 1
  val fetchInstMaxNum        = 2 // 单次取指
  val issueInstInfoMaxNum    = 2 // 发射数量
  val commitNum              = 2 // 单次提交数量
  val pipelineNum            = 3 // number of pipeline
  val reservationStationDeep = 3 // 保留站深度
  val csrRegsReadNum         = 1
  val csrRegsWriteNum        = 1

  val csrIssuePipelineIndex       = 1 // csr 相关指令在第1条流水线
  val loadStoreIssuePipelineIndex = 0 // load & store相关指令在第0条流水线
  val jumpBranchPipelineIndex     = 1
  val exePassWbNum                = pipelineNum - 1

  val dataForwardInputNum = 2

  object Width {
    val exeSel = log2Ceil(ExeInst.Sel.count + 1).W
    val exeOp  = log2Ceil(ExeInst.Op.count + 1).W

    object Rob {
      val _length = 15
      val id      = log2Ceil(_length + 1).W
    }

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
      val _addr       = 8 // TODO: Choose an optimal value
      val _byteOffset = log2Ceil(Count.DCache.dataPerLine) + log2Ceil(wordLength / byteLength)
      val _dataLine   = Count.DCache.dataPerLine * spec.Width.Mem._data
      val _tag        = spec.Width.Mem._addr - _addr - _byteOffset

      val addr       = _addr.W
      val byteOffset = _byteOffset.W
      val tag        = _tag.W
      val dataLine   = _dataLine.W
    }

    object ICache {
      val _addr       = 8 // TODO: Choose an optimal value
      val _byteOffset = log2Ceil(Count.ICache.dataPerLine) + log2Ceil(wordLength / byteLength)
      val _dataLine   = Count.ICache.dataPerLine * spec.Width.Mem._data
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
      val dataPerLine = 16 // TODO: One data line is 64 bytes
      val sizePerRam  = math.pow(2, Width.DCache._addr).toInt
    }

    object ICache {
      val setLen      = 2 // Also the number of RAMs for data; TODO: Choose an optimal value
      val dataPerLine = 16 // TODO: One data line is 64 bytes
      val sizePerRam  = math.pow(2, Width.ICache._addr).toInt
    }

    object Tlb {
      val num      = 32
      val transNum = 2
    }

    object Mem {
      val storeQueueLen = 8
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

  object NaiiveFetchStageState extends ChiselEnum {
    val idle, request, waitQueue = Value
  }

  object ExeStageState extends ChiselEnum {
    val nonBlocking, blocking = Value
  }

  object AluState extends ChiselEnum {
    val nonBlocking, blocking = Value
  }
}
