package utils

import chisel3._
import chisel3.util._

class MultiCounter(
  maxCount:      Int,
  maxIncNum:     Int,
  init:          Int     = 0,
  supportSet:    Boolean = false,
  needRecurrent: Boolean = true)
    extends Module {
  require(maxCount >= maxIncNum)
  val value_w = log2Ceil(maxCount)
  val inc_w   = log2Ceil(maxIncNum + 1)

  val isMaxCountPow2: Boolean = isPow2(maxCount)

  val io = IO(new Bundle {
    // inc =/= `11`
    val inc   = Input(UInt(inc_w.W))
    val flush = Input(Bool())
    // one clock delay
    val value = Output(UInt(value_w.W))
    // no delay
    val incResults = Output(Vec(maxCount + 1, UInt(value_w.W)))

    val setPort = if (supportSet) Some(Input(Valid(UInt(value_w.W)))) else None
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
      if (isMaxCountPow2 || !needRecurrent) {
        incResult := counter + inc.U
      } else {
        val rawAdd = Wire(UInt((value_w + 1).W))
        rawAdd := counter +& inc.U
        incResult := Mux(
          rawAdd >= maxCount.U,
          rawAdd - maxCount.U, // 溢出
          rawAdd
        )
      }
  }

  counter := incResults(io.inc)

  if (supportSet) {
    when(io.setPort.get.valid) {
      counter := io.setPort.get.bits
    }
  }

  when(io.flush) {
    io.value := init.U
    counter  := init.U
  }
}
