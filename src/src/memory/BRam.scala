package memory

import chisel3._
import chisel3.util._

class SingleBRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite = Input(Bool())
    val addr    = Input(UInt(addrWidth.W))
    val dataIn  = Input(UInt(dataWidth.W))
    val dataOut = Output(UInt(dataWidth.W))
  })

  val data = RegInit(VecInit(Seq.fill(size)(0.U(dataWidth.W))))

  // Read
  io.dataOut := RegNext(data(io.addr))

  // Write
  when(io.isWrite) {
    data(io.addr) := io.dataIn
  }
}

class SimpleDualBRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite   = Input(Bool())
    val readAddr  = Input(UInt(addrWidth.W))
    val writeAddr = Input(UInt(addrWidth.W))
    val dataIn    = Input(UInt(dataWidth.W))
    val dataOut   = Output(UInt(dataWidth.W))
  })

  val data = RegInit(VecInit(Seq.fill(size)(0.U(dataWidth.W))))

  // Read
  io.dataOut := RegNext(data(io.readAddr))

  // Write
  when(io.isWrite) {
    data(io.writeAddr) := io.dataIn
  }
}
