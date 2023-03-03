import chisel3._
import chisel3.util._

package object spec {
  // Immutable definitions according to LA32R documentation go here

  val wordLength = 32
  val wordLog = log2Ceil(wordLength)

  val zeroWord = 0.U(wordLength.W)

  object Width {
    val inst = wordLength.W
    object Reg {
      val addr = wordLog.W
      val data = wordLength.W
    }
  }

  object Count {
    val reg = wordLength
  }
}
