package pipeline.execution

import chisel3._
import chisel3.util._
import spec._

/** 并行计算前导0数量（2022）
  */
class Clz extends Module {

  val io = IO(new Bundle {
    val input  = Input(UInt(wordLength.W))
    val output = Output(UInt(wordLog.W))
  })

  // 每4位判断是否全0
  val subClz = WireDefault(
    VecInit(
      Seq
        .range(0, 8)
        .map(i => {
          ~io.input(i * 4 + 3, i * 4).orR
        })
    ).asUInt
  )

  // 每4位中，前3位的前导0数量
  val subsubClzTable = VecInit(
    "b11".U(2.W), // 000
    "b10".U(2.W), // 001
    "b01".U(2.W), // 010
    "b01".U(2.W), // 011
    "b00".U(2.W), // 100
    "b00".U(2.W), // 101
    "b00".U(2.W), // 110
    "b00".U(2.W) // 111
  )

  // 分成的8块的前3位前导0的数量
  val subsubClz = WireDefault(
    VecInit(
      Seq
        .range(0, 8)
        .map(i => {
          subsubClzTable(io.input(i * 4 + 3, i * 4 + 1))
        })
    )
  )

  val clzResult4 = Wire(Bool())
  val clzResult3 = Wire(Bool())
  val clzResult2 = Wire(Bool())

  clzResult4 := subClz(7, 4).andR // upper 16 all zero
  clzResult3 := Mux(
    clzResult4,
    subClz(3, 2).andR, // upper 24 all zero
    subClz(7, 6).andR // upper  8 all zero
  )

  clzResult2 := (subClz(7) & ~subClz(6)) | // upper 4 all zero
    (subClz(7, 5).andR & ~subClz(4)) | // upper 12
    (subClz(7, 3).andR & ~subClz(2)) | // upper 20
    (subClz(7, 1).andR & ~subClz(0)) // upper 28

  /** 切分成4块，分别求解clzResult末两位值 如末8位，i = 0，若subClz(1)===1，7-4位全0，看3-0位 否则7-4位有1，看7-4位
    */

  val clzResult10Selector = (VecInit(
    Seq.range(3, -1, -1).map(i => subsubClz(Cat(i.U(2.W), ~subClz(i * 2 + 1))))
  ))

  val clzResult10 = clzResult10Selector(Cat(clzResult4, clzResult3))

  io.output := Cat(clzResult4, clzResult3, clzResult2, clzResult10)
}
