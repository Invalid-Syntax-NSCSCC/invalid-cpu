package pipeline.execution

import chisel3._
import chisel3.util._
import spec._
import chisel3.internal.firrtl.DefRegInit
import pipeline.execution.bundles.MulDivInstNdPort

// it takes 2 cycles
class Mul extends Module {

  val io = IO(new Bundle {
    val mulInst   = Flipped(Decoupled(new MulDivInstNdPort))
    val mulResult = Decoupled(UInt(doubleWordLength.W))
  })

  val op  = WireDefault(io.mulInst.bits.op)
  val lop = WireDefault(io.mulInst.bits.leftOperand)
  val rop = WireDefault(io.mulInst.bits.rightOperand)

  // 是否为无符号乘法
  val isUnsigned = WireDefault(
    VecInit(
      ExeInst.Op.mulhu
    ).contains(op)
  )

  // Booth
  val boothWeights = Wire(Vec(16, SInt(4.W)))
  boothWeights.zipWithIndex.foreach {
    case (weight, index) =>
      val shiftNum = 2 * index
      val a3       = Wire(UInt(4.W))
      a3 := rop(shiftNum + 1).asUInt
      val a2 = Wire(UInt(4.W))
      a2 := rop(shiftNum).asUInt
      val a1 = Wire(UInt(4.W))
      if (index != 0) {
        a1 := rop(shiftNum - 1).asUInt
      } else {
        a1 := 0.U
      }
      weight := a1.asSInt + a2.asSInt + -(a3.asSInt << 1).asSInt
      if (index == 15) {
        when(isUnsigned) {
          weight := a1.asSInt + a2.asSInt + (a3.asSInt << 1)
        }
      }
  }

  val boothResults = Wire(Vec(16, UInt(doubleWordLength.W)))
  boothResults.lazyZip(boothWeights).zipWithIndex.foreach {
    case ((result, weight), index) =>
      val shiftNum   = 2 * index
      val resultSInt = Wire(SInt(doubleWordLength.W))
      // resultSInt := ((lop.asSInt * weight) << shiftNum)
      resultSInt := (
        ((lop.asSInt << shiftNum).asSInt * weight(0) + (lop.asSInt << (shiftNum + 1)).asSInt * weight(1)) +
          ((lop.asSInt << (shiftNum + 2)).asSInt * weight(2) - (lop.asSInt << (shiftNum + 3)).asSInt * weight(3))
      )
      when(isUnsigned) {
        resultSInt := ((Cat(false.B, lop).asSInt * weight) << shiftNum)
      }
      result := resultSInt.asUInt
  }

  // wallance
  def s(a: UInt, b: UInt, c: UInt): UInt = {
    a ^ b ^ c
  }

  def c(a: UInt, b: UInt, c: UInt): UInt = {
    (((a & b) | (a & c) | (b & c)) << 1).asUInt
  }

  val wallance1 = Wire(Vec(11, UInt(doubleWordLength.W)))
  val wallance2 = Wire(Vec(8, UInt(doubleWordLength.W)))
  val wallance3 = Wire(Vec(6, UInt(doubleWordLength.W)))
  val wallance4 = Wire(Vec(4, UInt(doubleWordLength.W)))
  val wallance5 = Wire(Vec(3, UInt(doubleWordLength.W)))
  val wallance6 = Wire(Vec(2, UInt(doubleWordLength.W)))

  wallance6(0) := s(wallance5(0), wallance5(1), wallance5(2))
  wallance6(1) := c(wallance5(0), wallance5(1), wallance5(2))

  wallance5(0) := s(wallance4(0), wallance4(1), wallance4(2))
  wallance5(1) := c(wallance4(0), wallance4(1), wallance4(2))
  wallance5(2) := wallance4(3)

  Seq.range(0, 2).foreach { index =>
    wallance4(index * 2)     := s(wallance3(index * 3), wallance3(index * 3 + 1), wallance3(index * 3 + 2))
    wallance4(index * 2 + 1) := c(wallance3(index * 3), wallance3(index * 3 + 1), wallance3(index * 3 + 2))
  }

  Seq.range(0, 2).foreach { index =>
    wallance3(index * 2)     := s(wallance2(index * 3), wallance2(index * 3 + 1), wallance2(index * 3 + 2))
    wallance3(index * 2 + 1) := c(wallance2(index * 3), wallance2(index * 3 + 1), wallance2(index * 3 + 2))
  }
  wallance3(4) := wallance2(6)
  wallance3(5) := wallance2(7)

  Seq.range(0, 3).foreach { index =>
    wallance2(index * 2)     := s(wallance1(index * 3), wallance1(index * 3 + 1), wallance1(index * 3 + 2))
    wallance2(index * 2 + 1) := c(wallance1(index * 3), wallance1(index * 3 + 1), wallance1(index * 3 + 2))
  }
  wallance2(6) := wallance1(9)
  wallance2(7) := wallance1(10)

  Seq.range(0, 5).foreach { index =>
    wallance1(index * 2)     := s(boothResults(index * 3), boothResults(index * 3 + 1), boothResults(index * 3 + 2))
    wallance1(index * 2 + 1) := c(boothResults(index * 3), boothResults(index * 3 + 1), boothResults(index * 3 + 2))
  }
  wallance1(10) := boothResults(15)

  val resultReg = RegInit(0.U(doubleWordLength.W))
  resultReg         := (wallance6(0) + wallance6(1))
  io.mulResult.bits := resultReg

  val outValid = RegNext(io.mulInst.valid, false.B)

  io.mulInst.ready   := ~outValid
  io.mulResult.valid := outValid
}
