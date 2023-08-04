package execution

import chisel3._
import chisel3.util._
import execution.bundles.MulDivInstNdPort
import spec._

object DivState extends ChiselEnum {
  val free, clz, calc = Value
}

// Attention : 如果运行时输入数据，输入无效
class Div extends Module {

  val io = IO(new Bundle {
    val divInst = Input(Valid(new MulDivInstNdPort))
    val divResult = Output(Valid(new Bundle {
      val quotient  = UInt(wordLength.W) // 商
      val remainder = UInt(wordLength.W) // 余数
    }))
    val isFlush = Input(Bool())
  })

  val stateReg           = RegInit(DivState.free)
  val cyclesRemainingReg = RegInit(0.U((wordLog - 1).W))

  // State : free
  // prepare data

  def getSign(data: Bits, width: Integer): Bool = {
    data(width - 1)
  }

  val isSigned = io.divInst.bits.isSigned

  val dividend = io.divInst.bits.leftOperand
  val divisor  = io.divInst.bits.rightOperand

  val dividendSign = getSign(dividend, wordLength)
  val divisorSign  = getSign(divisor, wordLength)

  val quotientReg  = RegInit(zeroWord)
  val remainderReg = RegInit(zeroWord)

  val quotientSignReg  = RegNext(isSigned && (dividendSign ^ divisorSign), false.B)
  val remainderSignReg = RegNext(isSigned && dividendSign, false.B)

  val dividendAbsReg = RegNext(
    Mux(
      isSigned && dividendSign,
      (~dividend).asUInt + 1.U,
      dividend
    ),
    0.U
  )
  val divisorAbsReg = RegNext(
    Mux(
      isSigned && divisorSign,
      (~divisor).asUInt + 1.U,
      divisor
    ),
    0.U
  )

  // State : clz
  // counting clz

  // 前导0数量
  val dividendClz = Wire(UInt(wordLog.W))
  val divisorClz  = Wire(UInt(wordLog.W))
  val clzDelta    = divisorClz - dividendClz

  val dividendClzBlock = Module(new Clz)
  val divisorClzBlock  = Module(new Clz)

  dividendClzBlock.io.input := dividendAbsReg
  divisorClzBlock.io.input  := divisorAbsReg
  dividendClz               := dividendClzBlock.io.output
  divisorClz                := divisorClzBlock.io.output

  val divisorGreaterThanDividend = WireDefault(divisorAbsReg > dividendAbsReg)

  val shiftedDivisorReg = RegInit(zeroWord)
  shiftedDivisorReg := zeroWord

  // State : calc

  // 处理divisor移动奇数次情况，对上述多移动1位
  // sub2x = remainder - 2 * shiftDivisor
  val sub2x = Wire(UInt((wordLength + 2).W))
  // sub2x := Cat(0.U(1.W), remainder) -& Cat(shiftedDivisor, 0.U(1.W))
  sub2x := remainderReg -& (shiftedDivisorReg << 1).asUInt

  // shiftDivider过大
  val sub2xOverflow = WireDefault(getSign(sub2x, wordLength + 2))
  val sub2xToss     = WireDefault(sub2x(wordLength))
  val sub2xValue    = WireDefault(sub2x(wordLength - 1, 0))

  // 比较remainder和divisor移动偶数次数

  val sub1x = Wire(UInt((wordLength + 1).W))
  sub1x := Mux(
    sub2xOverflow,
    // 奇数次溢出
    // sub1x = remainder - shiftDivisor
    Cat(sub2xToss, sub2xValue) + shiftedDivisorReg,
    // 奇数次未溢出
    // sub1x = remainder - 3 * shiftDivisor
    Cat(sub2xToss, sub2xValue) - shiftedDivisorReg
  )

  val sub1xOverflow = WireDefault(getSign(sub1x, wordLength + 1))
  val sub1xValue    = WireDefault(sub1x(wordLength - 1, 0))

  val newQuotientBits = WireDefault(
    Cat(~sub2xOverflow, ~sub1xOverflow)
  )

  val cyclesRemainingSub1 = Wire(UInt(wordLog.W))
  cyclesRemainingSub1 := cyclesRemainingReg -& 1.U

  val isTerminateReg = RegInit(false.B)
  isTerminateReg := false.B

  io.divResult.valid := false.B
  io.divResult.bits  := DontCare

  switch(stateReg) {
    is(DivState.free) {
      when(io.divInst.valid) {
        stateReg := DivState.clz
      }
    }
    is(DivState.clz) {
      when(divisorGreaterThanDividend) {
        stateReg                    := DivState.free
        io.divResult.valid          := true.B
        io.divResult.bits.remainder := dividend
        io.divResult.bits.quotient  := zeroWord
      }.otherwise {
        stateReg := DivState.calc
      }

      // prepare init data for calculate
      shiftedDivisorReg  := divisorAbsReg << Cat(clzDelta(wordLog - 1, 1), 0.U(1.W))
      remainderReg       := dividendAbsReg
      quotientReg        := zeroWord
      cyclesRemainingReg := clzDelta(wordLog - 1, 1)
    }
    is(DivState.calc) {
      shiftedDivisorReg := shiftedDivisorReg >> 2
      remainderReg := Mux(
        !newQuotientBits.orR,
        remainderReg, // stay the same
        Mux(
          sub1xOverflow,
          sub2xValue, // sub2x = remainder - 2 * shiftDivisor
          sub1xValue // sub1x = remainder - (3 or 1) * shiftDivisor
        )
      )
      // quotient = quotient << 2 + new bits
      quotientReg        := Cat(quotientReg(wordLength - 3, 0), newQuotientBits)
      cyclesRemainingReg := cyclesRemainingSub1(wordLog - 2, 0)
      // io.divResult.bits.quotient := RegNext(
      //   Mux(
      //     quotientSignReg,
      //     (~quotientReg) + 1.U,
      //     quotientReg
      //   )
      // )
      // io.divResult.bits.remainder := RegNext(
      //   Mux(
      //     remainderSignReg,
      //     (~remainderReg) + 1.U,
      //     remainderReg
      //   )
      // )

      isTerminateReg := getSign(cyclesRemainingSub1, wordLog)
      when(isTerminateReg) {
        stateReg           := DivState.free
        io.divResult.valid := true.B
        io.divResult.bits.quotient := Mux(
          quotientSignReg,
          (~quotientReg).asUInt + 1.U,
          quotientReg
        )
        io.divResult.bits.remainder := Mux(
          remainderSignReg,
          (~remainderReg).asUInt + 1.U,
          remainderReg
        )
      }
    }
  }

  when(io.isFlush) {
    stateReg := DivState.free
  }
}
