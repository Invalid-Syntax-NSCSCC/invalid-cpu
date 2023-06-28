package frontend.bpu

import chisel3._
import chisel3.util._
import spec._

//return address stack
class RAS(
  entryNum: Int = Param.BPU.RAS.entryNum,
  addr:     Int = spec.Width.Mem._addr)
    extends Module {
  val addrWidth    = log2Ceil(addr)
  val pointerWidth = log2Ceil(entryNum)
  val io = IO(new Bundle {
    val push     = Input(Bool())
    val pop      = Input(Bool())
    val callAddr = Input(UInt(addrWidth.W))
    val topAddr  = Output(UInt(addrWidth.W))
  })
  // Data structure
  // use a Vec to act as a stack
  val lutram = RegInit(VecInit(Seq.fill(addrWidth)(0.U((entryNum.U)))))

  // Signal defines
  val newIndex  = WireInit(0.U(pointerWidth.W))
  val readIndex = RegInit(0.U(pointerWidth.W))

  // Index
  newIndex  := readIndex + io.push - io.pop
  readIndex := RegNext(newIndex)

  // Data
  when(io.push) {
    lutram(newIndex) := RegNext(io.callAddr)
  }

  // Output
  io.topAddr := lutram(readIndex)
}
