package utils
import chisel3._
import chisel3.util._

// 必须保证input只有一个valid
class MultiMux1[T <: Data](length: Int, tFactory: => T, blankT: => T) extends Module {
  val io = IO(new Bundle {
    val inputs = Input(Vec(length, Valid(tFactory)))
    val output = Valid(tFactory)
  })

  io.output.valid := io.inputs.map(_.valid).reduce(_ || _)
  val flatten = Wire(Vec(length, tFactory))
  for (i <- 0 until length) {
    flatten(i) := Mux(io.inputs(i).valid, io.inputs(i).bits, blankT)
  }
  io.output.bits := VecInit(flatten.map(_.asUInt)).reduceTree(_ | _).asTypeOf(tFactory)
}

class MultiMux2[T <: Data](length1: Int, length2: Int, tFactory: => T, blankT: => T) extends Module {
  val io = IO(new Bundle {
    val inputs = Input(Vec(length1, Vec(length2, Valid(tFactory))))
    val output = Valid(tFactory)
  })

  io.output.valid := io.inputs.map(_.map(_.valid).reduce(_ || _)).reduce(_ || _)
  val flatten = Wire(Vec(length1 * length2, tFactory))
  for (i <- 0 until length1) {
    for (j <- 0 until length2) {
      flatten(i * length2 + j) := Mux(io.inputs(i)(j).valid, io.inputs(i)(j).bits, blankT)
    }
  }
  io.output.bits := VecInit(flatten.map(_.asUInt)).reduceTree(_ | _).asTypeOf(tFactory)
}
