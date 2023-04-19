package utils

import chisel3._
import chisel3.util._

class MinFinder(num: Int, wordLength: Int) extends Module {
  val numLog = log2Ceil(num)
  val io = IO(new Bundle {
    val values = Input(Vec(num, UInt(wordLength.W)))
    val masks  = Input(Vec(num, Bool()))
    val index  = Output(UInt(numLog.W))
  })

  // word length * num
  val compareVec = VecInit(Seq.range(0, wordLength).map { bitNum =>
    VecInit(io.values.map(_(bitNum))).asUInt
  })

  val flags = VecInit(compareVec.scanRight(io.masks.asUInt)((high, now) => {
    val tmp = (~high | now)
    Mux(
      tmp.andR,
      high,
      ~tmp
    )
  }))

  io.index := 0.U
  flags(0).asBools.zipWithIndex.foreach {
    case (flag, index) =>
      when(flag) {
        io.index := index.U
      }
  }

  // val flag = VecInit(Seq.range(wordLength-1, -1, -1).map{bitNum =>
  //   if (bitNum == wordLength - 1) {
  //     Mux(
  //       (~io.masks.asUInt | compareVec(bitNum)).andR,
  //       io.masks.asUInt,
  //       ~(~io.masks.asUInt | compareVec(bitNum))
  //     )
  //   }else {
  //     // Mux(
  //       (~flag(i+1))
  //     // )
  //   }
  // })
}
