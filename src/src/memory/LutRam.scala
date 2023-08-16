package memory

import chisel3._
import chisel3.util._

class SingleLutRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite = Input(Bool())
    val addr    = Input(UInt(addrWidth.W))
    val dataIn  = Input(UInt(dataWidth.W))
    val dataOut = Output(UInt(dataWidth.W))
  })

  val ram = SyncReadMem(size, UInt(dataWidth.W), SyncReadMem.ReadFirst)

  // Read
  io.dataOut := ram.read(io.addr)

  // Write
  when(io.isWrite) {
    ram(io.addr) := io.dataIn
  }
}

class SimpleDualLutRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite   = Input(Bool())
    val readAddr  = Input(UInt(addrWidth.W))
    val writeAddr = Input(UInt(addrWidth.W))
    val dataIn    = Input(UInt(dataWidth.W))
    val dataOut   = Output(UInt(dataWidth.W))
  })

  val ram = SyncReadMem(size, UInt(dataWidth.W), SyncReadMem.ReadFirst)

  // Read
  io.dataOut := ram.read(io.readAddr)

  // Write
  when(io.isWrite) {
    ram(io.writeAddr) := io.dataIn
  }
}
