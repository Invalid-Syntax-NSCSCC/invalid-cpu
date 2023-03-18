package pipeline.execution

import chisel3._
import chisel3.util._
import spec._
import pipeline.execution.bundles.AluInstNdPort
import chisel3.internal.firrtl.DefRegInit

// 花费一周期完成
class Mul extends Module {

  val io = IO(new Bundle {
    val mulInst = Flipped(Decoupled(new AluInstNdPort))
    val mulResult = Decoupled(UInt(doubleWordLength.W))
    // val isRunning = (Output(Bool()))
  })


  val op = WireDefault(io.mulInst.bits.op)
  val lop = WireDefault(io.mulInst.bits.leftOperand)
  val rop = WireDefault(io.mulInst.bits.rightOperand)

  // // 是否为有符号乘法
  // val isSigned = WireDefault(VecInit(
  //     ExeInst.Op.mul,
  //     ExeInst.Op.mulh
  //   ).contains(op)
  // )
  val allSign = VecInit(
    ExeInst.Op.mul,
    ExeInst.Op.mulh
  )

  val isSignedWoWire = allSign.contains(op)
  val isSigned = WireDefault(isSignedWoWire)


  val result = RegInit(0.U(doubleWordLength.W))

  val outValid = RegNext(io.mulInst.valid, false.B)


  result := Mux(
    isSigned,
    (lop.asSInt * lop.asSInt).asUInt,
    lop * rop
  )

  io.mulInst.ready := ~outValid
  io.mulResult.valid := outValid
  io.mulResult.bits := result
  // io.isRunning := io.mulInst.valid


}