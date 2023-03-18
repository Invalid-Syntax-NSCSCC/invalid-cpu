package pipeline.execution

import chisel3._
import chisel3.util._
import spec._
import pipeline.execution.bundles.AluInstNdPort

// Attention : 如果运行时输入数据，输入无效
class Div extends Module {

  val io = IO(new Bundle {
    val divInst = Flipped(Decoupled(new AluInstNdPort))
    val divResult = Decoupled(new Bundle {
      val quotient = Output(UInt(wordLength.W)) // 商
      val remainder = Output(UInt(wordLength.W)) // 余数
    })
    val isRunning = (Output(Bool()))
  })

  // 正在运行
  val running = RegInit(false.B)
  io.isRunning := running// | io.divInst.valid

  val inputReady = WireDefault(~running)
  io.divInst.ready := inputReady

  // 开始 正在运算或除零，忽略
  val start = WireDefault(io.divInst.valid & inputReady & io.divInst.bits.rightOperand.orR)

  

  val op =        WireDefault(io.divInst.bits.op)
  // 被除数
  val dividend =  WireDefault(io.divInst.bits.leftOperand) 
  // 除数
  val divisor =   WireDefault(io.divInst.bits.rightOperand) 

  val quotient =  RegInit(zeroWord)
  val remainder = RegInit(zeroWord)

  // 是否为有符号
  val isSigned = WireDefault(VecInit(
    ExeInst.Op.div,
    ExeInst.Op.mod
  ).contains(op))

  def getSign(data: Bits, width: Integer): Bool = {
    data(width-1)
  }

  def getAbs(data: UInt, sign: Bool) : UInt = {
    Fill(wordLength, sign) ^ data + sign.asUInt
  }

  val dividendSign = getSign(dividend, wordLength)
  val divisorSign = getSign(divisor, wordLength)

  val quotientSign = isSigned & (dividendSign ^ divisorSign)
  val remainderSign = isSigned & dividendSign

  val dividendAbs = Mux(
    isSigned,
    getAbs(dividend, dividendSign & isSigned),
    dividend
  )
  val divisorAbs = Mux(
    isSigned,
    getAbs(divisor, divisorSign & isSigned),
    divisor
  )

  // 前导0数量
  val dividendClz = Wire(UInt(wordLog.W))
  val divisorClz = Wire(UInt(wordLog.W))

  val dividendClzBlock = Module(new Clz)
  val divisorClzBlock = Module(new Clz)
  dividendClzBlock.io.input := dividendAbs
  divisorClzBlock.io.input  := divisorAbs
  dividendClz := dividendClzBlock.io.output
  divisorClz  := divisorClzBlock.io.output

  

  val divisorSubDividend = Wire(UInt((wordLog+1).W))
  divisorSubDividend := (divisorClz -& dividendClz)
  // 1 if divisor > dividend
  val divisorGreaterThanDividend = WireDefault(
    getSign(divisorSubDividend, wordLog+1)
  )

  // if divisor > dividend
  val clzDelta = divisorSubDividend(wordLog-1,0)

  val shiftedDivisor = RegInit(zeroWord)

  shiftedDivisor := Mux(
      running,
      // >> 2
      shiftedDivisor(wordLength-1, 2),
      // divisor 左移到dividend的位置或其右边1位
      divisorAbs << Cat(clzDelta(wordLog-1,1),0.U(1.W)) //Rounding down when CLZ_delta is odd
    )
  
  // 处理divisor移动奇数次情况，对上述多移动1位
  // sub2x = remainder - 2 * shiftDivisor
  val sub2x = Wire(UInt((wordLength+2).W))
  // sub2x := Cat(0.U(1.W), remainder) -& Cat(shiftedDivisor, 0.U(1.W))
  sub2x := remainder -& (shiftedDivisor << 1)

  // shiftDivider过大
  val sub2xOverflow = WireDefault(getSign(sub2x, wordLength+2))
  val sub2xToss = WireDefault(sub2x(wordLength))
  val sub2xValue = WireDefault(sub2x(wordLength-1, 0))

  // 比较remainder和divisor移动偶数次数
  
  val sub1x = Wire(UInt((wordLength+1).W))
  sub1x := Mux(
    sub2xOverflow,
    // 奇数次溢出
    // sub1x = remainder - shiftDivisor
    Cat(sub2xToss, sub2xValue) + shiftedDivisor,
    // 奇数次未溢出
    // sub1x = remainder - 3 * shiftDivisor
    Cat(sub2xToss, sub2xValue) - shiftedDivisor
  )

  val sub1xOverflow = WireDefault(getSign(sub1x, wordLength+1))
  val sub1xValue = WireDefault(sub1x(wordLength-1, 0))

  val newQuotientBits = WireDefault(
    Cat(~sub2xOverflow, ~sub1xOverflow)
  )

  // 处理商
  when (start) {
    quotient := zeroWord
  }.elsewhen(running) {
    // quotient = quotient << 2 + new bits
    quotient := Cat(quotient(wordLength-3,0), newQuotientBits)
  }

  // 处理余数
  when (
    start ||
    ( running && newQuotientBits.orR ) // 若00 余数不变，则不处理
  ) {
    when (~running) {
      remainder := dividendAbs
    }.elsewhen(sub1xOverflow) {
      remainder := sub2xValue  // sub2x = remainder - 2 * shiftDivisor
    }.otherwise{
      remainder := sub1xValue  // sub1x = remainder - (3 or 1) * shiftDivisor
    }
  }

  // 剩余轮数
  val cyclesRemaining = RegInit(0.U((wordLog-1).W))
  val cyclesRemainingNext = WireDefault(0.U((wordLog-1).W))
  // 完成计算
  val terminate = WireDefault(false.B)

  val cyclesRemainingSub1 = Wire(UInt(wordLog.W))
  cyclesRemainingSub1 := cyclesRemaining -& 1.U
  terminate := getSign(cyclesRemainingSub1, wordLog)
  cyclesRemainingNext := cyclesRemainingSub1((wordLog-2), 0)

  cyclesRemaining := Mux(
    running,
    cyclesRemainingNext,    // run : sub 1
    clzDelta(wordLog-1, 1)  // not run : init
  )

  running := (running && ~terminate) ||
              (start && ~divisorGreaterThanDividend)

  val runningDelay = RegNext(running, false.B)
  val terminateDelay = RegNext(terminate, false.B)
  val startDelay = RegNext(start, false.B)
  val divisorGreaterThanDividendDelay = RegNext(divisorGreaterThanDividend, false.B)


  io.divResult.bits.quotient  := Mux(
    dividend.orR,
    Mux(
      quotientSign,
      ~quotient + 1.U,
      quotient
    ),
    zeroWord  // 被除数为0
  )
  io.divResult.bits.remainder := Mux(
    remainder.orR,
    Mux(
      remainderSign,
      ~remainder + 1.U,
      remainder
    ),
    zeroWord  // 被除数为0
  )

  // 运算完成
  io.divResult.valid := (runningDelay && terminateDelay) || 
                        (startDelay && divisorGreaterThanDividendDelay )
}
