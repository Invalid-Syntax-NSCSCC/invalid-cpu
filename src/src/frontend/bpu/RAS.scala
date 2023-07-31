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
    val push            = Input(Bool())
    val pop             = Input(Bool())
    val callAddr        = Input(UInt(spec.Width.Mem.addr))
    val topAddr         = Output(UInt(spec.Width.Mem.addr))
    val predictPush     = Input(Bool())
    val predictPop      = Input(Bool())
    val predictCallAddr = Input(UInt(spec.Width.Mem.addr))
    val predictError    = Input(Bool())
  })
  // Data structure
  // use a Vec to act as a stack
  val lutram        = RegInit(VecInit(Seq.fill(entryNum)(0.U(spec.Width.Mem.addr))))
  val predictLutram = RegInit(VecInit(Seq.fill(entryNum)(0.U(spec.Width.Mem.addr))))

  // Signal defines
  val newIndex  = WireInit(0.U(pointerWidth.W))
  val readIndex = RegInit(0.U(pointerWidth.W))

  val predictNewIndex  = WireInit(0.U(pointerWidth.W))
  val predictReadIndex = RegInit(0.U(pointerWidth.W))

  // Actually data
  // Index
  newIndex  := readIndex + io.push - io.pop
  readIndex := newIndex
  // Data
  when(io.push) {
    lutram(newIndex) := io.callAddr
  }

  // predict data
  // Index
  predictNewIndex  := predictReadIndex + io.predictPush - io.predictPop
  predictReadIndex := predictNewIndex
  // Data
//  when(io.predictPush) {
//    lutram(predictNewIndex) := io.predictCallAddr
//  }
  when(io.predictPush) {
    predictLutram(predictNewIndex) := io.predictCallAddr
  }

  // when branch predict error, reset ptr
  when(io.predictError) {
    predictNewIndex := newIndex
    predictLutram   := lutram
  }
  // Output
//  io.topAddr := lutram(predictReadIndex)
  io.topAddr := Mux(io.predictError, lutram(readIndex), predictLutram(predictReadIndex))
}
