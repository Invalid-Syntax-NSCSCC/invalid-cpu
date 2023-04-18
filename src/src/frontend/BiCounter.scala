package frontend

import chisel3._
import chisel3.util._

// 尝试双多发射的queue，未接入，不用管它
class BiCounter(count: Int) extends Module {
  require(count > 1)
  val w = log2Ceil(count).W
  val io = IO(new Bundle {
    // inc =/= `11`
    val inc   = Input(UInt(2.W))
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
}
