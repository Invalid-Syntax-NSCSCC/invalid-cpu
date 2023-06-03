package memory

import chisel3._
import chisel3.util._
import memory.bundles.{SimpleRamReadPort, SimpleRamWriteNdPort}
import spec._

class BRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite = Input(Bool())
    val addr    = Input(UInt(addrWidth.W))
    val dataIn  = Input(UInt(dataWidth.W))
    val dataOut = Output(UInt(dataWidth.W))
  })

  val data = Reg(Vec(size, UInt(dataWidth.W)))

  // Read
  io.dataOut := RegNext(data(io.addr))

  // Write
  when(io.isWrite) {
    data(io.addr) := io.dataIn
  }
}
