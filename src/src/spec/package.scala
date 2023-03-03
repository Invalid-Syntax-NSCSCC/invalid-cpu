import chisel3._

package object spec {
  val wordLength = 32
  val zeroWord = 0.U(wordLength.W)
  object Width {
    val inst = wordLength
  }
}
