package pipeline.complex.rob.lvt

import chisel3._
import chisel3.util._
import pipeline.complex.rob.lvt.bundles.{LvtReadPort, LvtWriteNdPort}

class LutRam[T <: Data](
  elemFactory: => T,
  blankElem:   => T,
  elemNum:     Int,
  hasFlush:    Boolean = false)
    extends Module {

  val addrWidth = log2Ceil(elemNum)

  val io = IO(new Bundle {
    val readPort  = new LvtReadPort(addrWidth, elemFactory)
    val writePort = Input(new LvtWriteNdPort(addrWidth, elemFactory))
    val flushPort = if (hasFlush) Some(Input(Valid(Vec(elemNum, elemFactory)))) else None
  })
  // val ram = RegInit(VecInit(Seq.fill(elemNum)(blankElem)))
  val ram = Reg(Vec(elemNum, elemFactory))
  ram.foreach { reg =>
    reg := reg
  }

  io.readPort.data := ram(io.readPort.addr)

  when(io.writePort.en) {
    ram(io.writePort.addr) := io.writePort.data
  }

  if (hasFlush) {
    when(io.flushPort.get.valid) {
      ram.zip(io.flushPort.get.bits).foreach {
        case (dst, src) =>
          dst := src
      }
    }
  }
}
