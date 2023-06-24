package axi

import chisel3._

class SetClrReg(val setOverClr: Boolean, val width: Int, val resetValue: Int) extends Module {
  val io = IO(new Bundle {
    val set    = Input(UInt(width.W))
    val clr    = Input(UInt(width.W))
    val result = Output(UInt(width.W))
  })

  val resultReg = RegInit(resetValue.U(width.W))
  resultReg := (if (setOverClr) {
                  io.set | (resultReg & (~io.clr).asUInt)
                } else {
                  (io.set | resultReg) & (~io.clr).asUInt
                })
  io.result := resultReg
}
