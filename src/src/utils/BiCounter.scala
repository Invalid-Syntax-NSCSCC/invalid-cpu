package utils

import chisel3._
import chisel3.util._

class BiCounter(count: Int, init: Int = 0) extends Module {
  require(count > 1)
  val w = log2Ceil(count).W
  val io = IO(new Bundle {
    // inc =/= `11`
    val inc   = Input(UInt(2.W))
    val flush = Input(Bool())
    // one clock delay
    val value = Output(UInt(w))
    // no delay
    val incOneResult = Output(UInt(w))
    val incTwoResult = Output(UInt(w))
  })

  val counter      = RegInit(init.U(w))
  val incOneResult = WireDefault(0.U(w))
  val incTwoResult = WireDefault(0.U(w))
  io.incOneResult := incOneResult
  io.incTwoResult := incTwoResult
  io.value        := counter

  // when(io.inc === 1.U) {
  when(counter === (count - 1).U) {
    incOneResult := 0.U
  }.otherwise {
    incOneResult := counter + 1.U
  }
  // }.elsewhen(io.inc === 2.U) {
  when(counter === (count - 2).U) {
    incTwoResult := 0.U
  }.elsewhen(counter === (count - 1).U) {
    incTwoResult := 1.U
  }.otherwise {
    incTwoResult := counter + 2.U
  }
  // }
  when(io.inc === 1.U) {
    counter := incOneResult
  }.elsewhen(io.inc === 2.U) {
    counter := incTwoResult
  }

  when(io.flush) {
    io.value := 0.U
    counter  := 0.U
  }
}
