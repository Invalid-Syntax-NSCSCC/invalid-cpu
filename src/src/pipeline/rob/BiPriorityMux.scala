package pipeline.rob

import chisel3._
import chisel3.util.log2Ceil

// 串行太多啦，待并行优化
class BiPriorityMux(num: Int = 8) extends Module {
  val numLog = log2Ceil(num)
  val io = IO(new Bundle {
    val inVector = Input(Vec(num, Bool()))
    val selectIndices = Output(
      Vec(
        2,
        new Bundle {
          val valid = Bool()
          val index = UInt(numLog.W)
        }
      )
    )
  })

  io.selectIndices.foreach { selectIndex =>
    selectIndex.valid := false.B
    selectIndex.index := 0.U
  }
  for (i <- 0 until num) {
    when(io.inVector(i)) {
      io.selectIndices(0).valid := true.B
      io.selectIndices(0).index := i.U
    }
  }

  for (i <- 0 until num) {
    when(io.inVector(i) && (i.U =/= io.selectIndices(0).index)) {
      io.selectIndices(1).valid := true.B
      io.selectIndices(1).index := i.U
    }
  }
}
