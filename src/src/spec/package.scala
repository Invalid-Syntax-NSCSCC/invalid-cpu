import chisel3._
import chisel3.util._

package object spec {
  // Immutable definitions according to LA32R documentation go here

  val wordLength       = 32
  val doubleWordLength = wordLength * 2
  val wordLog          = log2Ceil(wordLength)

  val zeroWord = 0.U(wordLength.W)

  object RegIndex {
    val r0 = 0.U
    val r1 = 1.U
  }

  object PipelineStageIndex {
    private var count = 0

    private def next = {
      val idx = count
      count += 1
      idx
    }
    val issueStage   = next
    val regReadStage = next
    val exeStage     = next
    val memStage     = next

    def getCount: Int = count
  }

  object Width {
    val inst = wordLength.W
    object Reg {
      val addr = wordLog.W
      val data = wordLength.W
    }

    object Op {
      val _2RI12 = 10.W
      val _2RI14 = 8.W
      val _2RI16 = 6.W
      val _2R    = 22.W
      val _3R    = 17.W
      val _4R    = 12.W
    }

    object Axi {
      val addr   = wordLength.W
      val data   = 32.W
      val strb   = 4.W // = (data / 8)
      val aruser = 1.W
      val ruser  = 1.W
      val awuser = 1.W
      val wuser  = 1.W
      val buser  = 1.W
    }
  }

  object Count {
    val reg = wordLength
  }

}
