package utils

import chisel3._
import chisel3.util._

class BiCounter(count: Int) extends Module {
  require(count > 1)
  val w = log2Ceil(count).W
  val io = IO(new Bundle {
    // inc =/= `11`
    val inc   = Input(UInt(2.W))
    val flush = Input(Bool())
    val value = Output(UInt(w))
  })

  val counter = RegInit(0.U(w))
  io.value := counter

  when(io.inc === 1.U) {
    when(counter === (count - 1).U) {
      counter := 0.U
    }.otherwise {
      counter := counter + 1.U
    }
  }.elsewhen(io.inc === 2.U) {
    when(counter === (count - 2).U) {
      counter := 0.U
    }.elsewhen(counter === (count - 1).U) {
      counter := 1.U
    }.otherwise {
      counter := counter + 2.U
    }
  }

  when(io.flush) {
    io.value := false.B
    counter  := 0.U
  }
}
