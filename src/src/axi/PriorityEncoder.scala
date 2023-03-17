package axi

import chisel3._
import chisel3.util._
import spec.Axi.Arb._

class PriorityEncoder(val width: Int) extends Module {
  val io = IO(new Bundle {
    val inputUnencoded  = Input(UInt(width.W))
    val outputValid     = Output(Bool())
    val outputEncoded   = Output(UInt(log2Ceil(width).W))
    val outputUnencoded = Output(UInt(width.W))
  })

  val levels = if (width > 2) log2Ceil(width) else 1
  val w      = math.pow(2, levels).toInt

  // pad input to even power of two
  val inputPadded = UInt(w.W)
  inputPadded := io.inputUnencoded

  val stageValid = Vec(levels, UInt((w / 2).W))
  val stageEnc   = Vec(levels, UInt((w / 2).W))

  // porcess input bits; generate valid bit and encoded bit for each pair
  for (n <- 0 until w / 2) {
    stageValid(0)(n) := inputPadded(n * 2 + 1, n * 2).orR
    stageEnc(0)(n)   := (if (lsbHighPriority) !inputPadded(n * 2 + 0) else inputPadded(n * 2 + 1))
  }

  // compress down to single valid bit and encoded bus
  for (l <- 1 until levels) {
    for (n <- 0 until w / math.pow(2, l + 1).toInt) {
      stageValid(l)(n) := stageValid(l - 1)(n * 2 + 1, n * 2).asUInt.orR
      // TODO： 👇 may be read-only?
      stageEnc(l)((n + 1) * (l + 1) - 1, n * (l + 1)) := Mux(
        if (lsbHighPriority) stageValid(l - 1)(n * 2 + 0) else !stageValid(l - 1)(n * 2 + 1),
        Cat(false.B, stageEnc(l - 1)((n * 2 + 1) * l - 1, (n * 2 + 0) * l)),
        Cat(true.B, stageEnc(l - 1)((n * 2 + 2) * l - 1, (n * 2 + 1) * l))
      )
    }
  }

  io.outputValid     := stageValid(levels - 1)(0)
  io.outputEncoded   := stageEnc(levels - 1)
  io.outputUnencoded := 1.U << io.outputEncoded
}
