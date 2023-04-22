package memory

import chisel3._
import chisel3.util._
import memory.bundles.{SimpleRamReadPort, SimpleRamWriteNdPort}

class SimpleRam(size: Int, dataWidth: Int, isDebug: Boolean = false, debugWriteNum: Int = 0) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val readPort  = new SimpleRamReadPort(addrWidth, dataWidth)
    val writePort = Input(new SimpleRamWriteNdPort(addrWidth, dataWidth))
    val debugPorts =
      if (isDebug)
        Some(
          Vec(debugWriteNum, Input(new SimpleRamWriteNdPort(addrWidth, dataWidth)))
        )
      else None
  })

  val data = RegInit(VecInit(Seq.fill(size)(0.U(dataWidth.W))))
  data := data

  // Read
  io.readPort.data := data(io.readPort.addr)

  // Write
  when(io.writePort.en) {
    data(io.writePort.addr) := io.writePort.data
  }

  // Debug: Prepare RAM
  io.debugPorts match {
    case Some(ports) =>
      ports.foreach { port =>
        when(port.en) {
          data(port.addr) := port.data
        }
      }
    case None =>
  }
}
