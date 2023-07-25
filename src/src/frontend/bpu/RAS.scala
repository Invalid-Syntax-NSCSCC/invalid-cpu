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
    val callAddr = Input(UInt(spec.Width.Mem.addr))
    val topAddr  = Output(UInt(spec.Width.Mem.addr))
  })
  // Data structure
  // use a Vec to act as a stack
  val lutram = RegInit(VecInit(Seq.fill(entryNum)(0.U(spec.Width.Mem.addr))))

  // Signal defines
  val newIndex  = WireInit(0.U(pointerWidth.W))
  val readIndex = RegInit(0.U(pointerWidth.W))

  // Index
  newIndex  := readIndex + io.push.asUInt - io.pop.asUInt
  readIndex := newIndex

  // Data
  when(io.push) {
    lutram(newIndex) := io.callAddr
  }

  // Output
  io.topAddr := lutram(readIndex)
}
