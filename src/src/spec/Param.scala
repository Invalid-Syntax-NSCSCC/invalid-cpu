package spec

import chisel3.util._
import chisel3.{ChiselEnum, _}

object Param {
  // Configurable self-defined parameters go here

  val useSimpleBackend = true

  // These options are one-hot
  val isChiplab        = true
  val isReleasePackage = false
  val isFullFpga       = false

  val usePmu = false || isChiplab // 性能计数器

  val isDiffTest                 = false || isChiplab
  val isOutOfOrderIssue          = true
  val isFullUncachedPatch        = true
  val isMmioDelay                = false || isChiplab || isFullFpga
  val isNoPrivilege              = false || isReleasePackage
  val isCacheOnPg                = false
  val isForcedCache              = false || isReleasePackage
  val isForcedUncached           = false
  val isBranchPredict            = true
  val isPredecode                = true
  val isOverideRas               = true
  val isFTBupdateRet             = true
  val isSpeculativeGlobalHistory = false

  val isWritebackPassThroughWakeUp = true
  val canIssueSameWbRegInsts       = true
  val isWakeUpPassThroughExe       = false // true && !isOutOfOrderIssue
  val instQueueCombineSel          = true // false : connect decode ; true : connect predecode
  val exeFeedBackFtqDelay          = true
  val isMainResWbEarly             = true

  val isOptimizedByMultiMux = true

  val instQueueLength        = 16
  val instQueueChannelNum    = 4
  val regFileReadNum         = 2
  val regFileWriteNum        = 1
  val scoreboardChangeNum    = 1 // 3
  val csrScoreBoardChangeNum = 1
  val instRegReadNum         = 2
  val fetchInstMaxNum        = 4 // 单次取指 must be 1,2,4,8... ( less than dataPerLine)
  val issueInstInfoMaxNum    = 2 // 发射数量
  val commitNum              = 1 // 单次提交数量
  val pipelineNum            = if (useSimpleBackend) issueInstInfoMaxNum else 3 // number of pipeline
  val dispatchOutQueueLength = 2
  val csrReadNum             = 1
  val csrWriteNum            = 1

  val csrIssuePipelineIndex       = 1 // csr 相关指令在第1条流水线
  val loadStoreIssuePipelineIndex = 0 // load & store相关指令在第0条流水线
  val jumpBranchPipelineIndex     = 1
  val exePassWbNum                = pipelineNum - 1

  val dataForwardInputNum = 2

  object Width {
    val exeSel = log2Ceil(ExeInst.Sel.count + 1).W
    val exeOp  = log2Ceil(ExeInst.Op.count + 1).W

    object ReservationStation {
      val _channelNum    = 2
      val _channelLength = 4
      val _length        = _channelLength * _channelNum
    }

    object Rob {
      val _length        = 16
      val _channelNum    = 4
      val _channelLength = _length / _channelNum
      val _id            = log2Ceil(_length)
      val id             = _id.W
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
      val _addr =
        if (isReleasePackage) 8 else 10 // TODO: Choose an optimal value (small value is suitible for difftest)
      val _byteOffset     = log2Ceil(Count.DCache.dataPerLine) + log2Ceil(wordLength / byteLength)
      val _dataLine       = Count.DCache.dataPerLine * spec.Width.Mem._data
      val _tag            = spec.Width.Mem._addr - _addr - _byteOffset
      val _indexOffsetMax = 12

      val addr       = _addr.W
      val byteOffset = _byteOffset.W
      val tag        = _tag.W
      val dataLine   = _dataLine.W
    }

    object ICache {
      val _addr =
        if (isReleasePackage) 8 else 10 // TODO: Choose an optimal value (small value is suitible for difftest)
      val _instOffset     = log2Ceil(wordLength / byteLength)
      val _fetchOffset    = log2Ceil(fetchInstMaxNum) + log2Ceil(wordLength / byteLength)
      val _byteOffset     = log2Ceil(Count.ICache.dataPerLine) + log2Ceil(wordLength / byteLength)
      val _dataLine       = Count.ICache.dataPerLine * spec.Width.Mem._data
      val _tag            = spec.Width.Mem._addr - _addr - _byteOffset
      val _indexOffsetMax = 12

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
      val dataPerLine = if (isReleasePackage) 8 else 16
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
      val MmioDelayMax  = 5
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
      val dCache = 2
    }
  }

  object BPU {
    val ftqSize     = 16
    val ftqPtrWidth = log2Ceil(ftqSize)

    object Width {
      val id = log2Ceil(ftqSize).W
    }

    object TagePredictor {
      //        ComponentTableDepth
      // predictor num = tagComponentNum + 1 (BasePredictor)
      val tagComponentTagWidth   = 12
      val tagComponentNum        = 4
      val componentHistoryLength = Seq(0, 11, 23, 53, 112)
      val componentTableDepth =
        Seq(8192, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024)
      val componentCtrWidth    = Seq(2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)
      val componentUsefulWidth = Seq(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
      // tage paper suggest 2-bits useful, but in order to save source, we use 1 bit(won't decrease ipc)
      val ghrLength   = componentHistoryLength(tagComponentNum) + ftqSize
      val ghrPtrWidth = log2Ceil(ghrLength)
    }

    object FTB {
      val nset = 1024
      val nway = 4
    }

    object RAS {
      val entryNum = 32
    }

    object BranchType {
      var count = 0
      private def next = {
        count += 1
        count.U
      }
      val cond   = 0.U
      val call   = next
      val ret    = next
      val uncond = next

      def width = log2Ceil(count + 1)
    }

    object GhrFixType {
      var count = 0

      private def next = {
        count += 1
        count.U
      }

      val commitRecover    = 0.U
      val exeFixJumpError  = next
      val exeUpdateJump    = next
      val exeRecover       = next
      val decodeUpdateJump = next
      val decodeBrExcp     = next

      def width = log2Ceil(count + 1)
    }
  }

  object SimpleFetchStageState extends ChiselEnum {
    val idle, requestInst, waitInst = Value
  }

  object NaiiveFetchStageState extends ChiselEnum {
    val idle, request, waitQueue = Value
  }
}
