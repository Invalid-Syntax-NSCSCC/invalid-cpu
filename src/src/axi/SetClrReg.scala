package axi

import chisel3._
import chisel3.util._

class SetClrReg(val setOverClr: Boolean, val width: Int, val resetValue: Int) extends Module {
  val io = IO(new Bundle {
    val set    = Input(UInt(width.W))
    val clr    = Input(UInt(width.W))
    val result = Output(UInt(width.W))
  })

  val resultReg = RegInit(resetValue.U(width.W))
  resultReg := (if (setOverClr) {
                  io.set | (resultReg & ~io.clr)
                } else {
                  (io.set | resultReg) & ~io.clr
                })
  io.result := resultReg
}
