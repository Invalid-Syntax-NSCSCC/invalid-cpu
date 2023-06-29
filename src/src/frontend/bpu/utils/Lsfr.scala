package frontend.bpu.utils

import chisel3._
import chisel3.util._

//translate lfsr system verilog code from https://github.com/openhwgroup/cva5/blob/master/core/lfsr.sv
//3-16 bit LFSRs with additional feedback to support full 2^N range
// Linear-feedback shift register
//lfsr is used to generate random number
class Lfsr(
  width:      Int = 3,
  NeedsReset: Int = 1)
    extends Module {
  // XNOR taps for LFSR from 3-16 bits wide (source: Xilinx xapp052)
  val tapNums = Seq(1, 1, 1, // Dummy entries for widths 0-2
    2, 2, 2, 2, 2, // Number of taps and indicies[3:0] for LFSRs width 3 to 16
    4, 2, 2, 2, // 8
    4, 4, 4, 2, 4)
  val tapIndiciess = Seq(
    Seq(0, 0, 0, 0),
    Seq(0, 0, 0, 0),
    Seq(0, 0, 0, 0),
    Seq(0, 0, 1, 2),
    Seq(0, 0, 2, 3),
    Seq(0, 0, 2, 4),
    Seq(0, 0, 4, 5),
    Seq(0, 0, 5, 6),
    Seq(3, 4, 5, 7),
    Seq(0, 0, 4, 8),
    Seq(0, 0, 8, 10),
    Seq(0, 3, 5, 11),
    Seq(0, 2, 3, 12),
    Seq(0, 2, 4, 13),
    Seq(0, 0, 13, 14),
    Seq(3, 12, 14, 15)
  )

  val io = IO(new Bundle {
    val en    = Input(Bool())
    val value = Output(UInt(width.W))
  })

  val num      = tapNums(width)
  val indicies = tapIndiciess(width)

  val feedbackInput = WireDefault(0.U(num.W))
  val feedback      = WireDefault(false.B)
  val value         = RegInit(0.U(width.W))
  ////////////////////////////////////////////////////
  // Implementation
  if (width == 2) {
    feedback := ~value(width - 1)
  } else {
    for (i <- 0 to num) {
      feedbackInput(i) := value(indicies(i))
    }
    // XNOR of taps and range extension to include all ones
    feedback := (!(feedbackInput.xorR)) ^ (value(width - 2, 0).orR)
  }

  when(io.en) {
    value := RegNext(Cat(value(width - 2, 0), feedback))
  }
  io.value := value

}
