package pipeline.mem

import chisel3._
import chisel3.util._
import pipeline.mem.bundles.{SimpleRamReadPort, SimpleRamWriteNdPort}
import spec._

class SimpleRam(size: Int, dataLength: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val readPort  = new SimpleRamReadPort(addrWidth, dataLength)
    val writePort = Input(new SimpleRamWriteNdPort(addrWidth, dataLength))
  })

  val data = RegInit(VecInit(Seq.fill(size)(0.U(dataLength.W))))
  data := data

  // Read
  io.readPort.data := data(io.readPort.addr)

  // Write
  when(io.writePort.en) {
    data(io.writePort.addr) := io.writePort.data
  }
}
