package pipeline.rob.lvt

import chisel3._
import chisel3.util._
import spec._
import pipeline.rob.lvt.bundles.LvtReadPort
import pipeline.rob.lvt.bundles.LvtWriteNdPort

class LvtLastValidTable(
  elemNum:  Int,
  readNum:  Int,
  writeNum: Int,
  hasFlush: Boolean = false)
    extends Module {
  val writeIdWidth = log2Ceil(writeNum)
  val addrWidth    = log2Ceil(elemNum)

  val io = IO(new Bundle {
    val readPorts = Vec(
      readNum,
      new LvtReadPort(addrWidth, UInt(writeIdWidth.W))
    )
    val writePorts = Vec(
      writeNum,
      Input(new LvtWriteNdPort(addrWidth, UInt(0.W)))
    )
    val isFlush = if (hasFlush) Some(Input(Bool())) else None
  })

  val lastValidRegs = RegInit(VecInit(Seq.fill(elemNum)(0.U(writeIdWidth.W))))
  io.writePorts.zipWithIndex.foreach {
    case (w, idx) =>
      when(w.en) {
        lastValidRegs(w.addr) := idx.U
      }
  }

  io.readPorts.foreach { r =>
    r.data := lastValidRegs(r.addr)
  }

  if (hasFlush) {
    when(io.isFlush.get) {
      lastValidRegs.foreach(_ := 0.U)
    }
  }
}
