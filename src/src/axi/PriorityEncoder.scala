package axi

import chisel3._
import chisel3.util._
import spec.Param.Axi.Arb._

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
  val inputPadded = Wire(UInt(w.W))
  inputPadded := io.inputUnencoded

  val stageValid = Wire(Vec(levels, UInt((w / 2).W)))
  val stageEnc   = Wire(Vec(levels, UInt((w / 2).W)))

  // porcess input bits; generate valid bit and encoded bit for each pair
  val stageValid0 = Wire(Vec(w / 2, Bool()))
  val stageEnc0   = Wire(Vec(w / 2, Bool()))
  for (n <- 0 until w / 2) {
    stageValid0(n) := inputPadded(n * 2 + 1, n * 2).orR
    stageEnc0(n)   := (if (lsbHighPriority) !inputPadded(n * 2 + 0) else inputPadded(n * 2 + 1))
  }
  stageValid(0) := stageValid0.asUInt
  stageEnc(0)   := stageEnc0.asUInt

  // compress down to single valid bit and encoded bus
  for (l <- 1 until levels) {
    val stageValidl = Wire(Vec(w / 2, Bool()))
    val stageEncl   = Wire(Vec(w / 2, Bool()))
    stageValidl := DontCare
    stageEncl   := DontCare
    for (n <- 0 until w / math.pow(2, l + 1).toInt) {
      stageValidl(n) := stageValid(l - 1)(n * 2 + 1, n * 2).asUInt.orR
      val data = Mux(
        if (lsbHighPriority) stageValid(l - 1)(n * 2 + 0) else !stageValid(l - 1)(n * 2 + 1),
        Cat(false.B, stageEnc(l - 1)((n * 2 + 1) * l - 1, (n * 2 + 0) * l)),
        Cat(true.B, stageEnc(l - 1)((n * 2 + 2) * l - 1, (n * 2 + 1) * l))
      ).asBools
      for (k <- 0 until (l + 1))
        stageEncl(k + n * (l + 1)) := data(k)
    }
    stageValid(l) := stageValidl.asUInt
    stageEnc(l)   := stageEncl.asUInt
  }

  io.outputValid     := stageValid(levels - 1)(0)
  io.outputEncoded   := stageEnc(levels - 1)
  io.outputUnencoded := 1.U << io.outputEncoded
}
