package utils

import chisel3._
import chisel3.util._

class MultiCounter(
  maxCount:  Int,
  maxIncNum: Int,
  init:      Int = 0)
    extends Module {
  require(maxCount > maxIncNum)
  val value_w = log2Ceil(maxCount + 1)
  val inc_w   = log2Ceil(maxIncNum + 1)
  val io = IO(new Bundle {
    // inc =/= `11`
    val inc   = Input(UInt(inc_w.W))
    val flush = Input(Bool())
    // one clock delay
    val value = Output(UInt(value_w.W))
    // no delay
    val incResults = Output(Vec(maxCount + 1, UInt(value_w.W)))
  })

  val counter    = RegInit(init.U(value_w.W))
  val incResults = Wire(Vec(maxCount + 1, UInt(value_w.W)))
  io.value := counter
  io.incResults.zip(incResults).foreach {
    case (dst, src) =>
      dst := src
  }

  incResults.zipWithIndex.foreach {
    case (incResult, inc) =>
      val rawAdd = Wire(UInt((value_w + 1).W))
      rawAdd := counter +& inc.U
      incResult := Mux(
        rawAdd >= maxCount.U,
        rawAdd - maxCount.U, // 溢出
        rawAdd
      )
  }

  counter := incResults(io.inc)

  when(io.flush) {
    io.value := 0.U
    counter  := 0.U
  }
}
