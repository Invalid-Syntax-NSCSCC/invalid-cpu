package memory

import chisel3._
import chisel3.util._
import spec._

class VBRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite = Input(Bool())
    val addr    = Input(UInt(addrWidth.W))
    val dataIn  = Input(UInt(dataWidth.W))
    val dataOut = Output(UInt(dataWidth.W))
  })

  val blackBox = Module(new single_readfirst_bram(size, dataWidth))

  blackBox.io.addra  := io.addr
  blackBox.io.dina   := io.dataIn
  blackBox.io.clka   := clock
  blackBox.io.wea    := io.isWrite
  blackBox.io.ena    := true.B
  blackBox.io.rsta   := reset
  blackBox.io.regcea := false.B
  io.dataOut         := blackBox.io.douta
}

class single_readfirst_bram(size: Int, dataWidth: Int)
    extends BlackBox(
      Map(
        "RAM_WIDTH" -> dataWidth,
        "RAM_DEPTH" -> size,
        "RAM_PERFORMANCE" -> "LOW_LATENCY"
      )
    ) {
  val io = IO(new Bundle {
    val addra  = Input(UInt(log2Ceil(size).W))
    val dina   = Input(UInt(dataWidth.W))
    val clka   = Input(Clock())
    val wea    = Input(Bool())
    val ena    = Input(Bool())
    val rsta   = Input(Bool())
    val regcea = Input(Bool())
    val douta  = Output(UInt(dataWidth.W))
  })
}
