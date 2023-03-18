import chisel3._
import chisel3.util._

package object spec {
  // Immutable definitions according to LA32R documentation go here

  val wordLength = 32
  val wordLog    = log2Ceil(wordLength)

  val zeroWord = 0.U(wordLength.W)

  object Width {
    val inst = wordLength.W
    object Reg {
      val addr = wordLog.W
      val data = wordLength.W
    }

    object Op {
      val _2RI12 = 10.W
    }

    object Axi {
      val addr   = wordLength
      val data   = 32
      val strb   = data / 8
      val aruser = 1
      val ruser  = 1
      val awuser = 1
      val wuser  = 1
      val buser  = 1
    }
  }

  object Count {
    val reg = wordLength
  }

}
