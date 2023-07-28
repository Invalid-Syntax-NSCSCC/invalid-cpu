package pipeline.rob.lvt

import chisel3._
import chisel3.util._
import spec._
import pipeline.rob.lvt.bundles._

class LiveValueTable[T <: Data](
  elemFactory: => T,
  blankElem:   => T,
  elemNum:     Int,
  readNum:     Int,
  writeNum:    Int,
  hasFlush:    Boolean = false)
    extends Module {
  val addrWidth = log2Ceil(elemNum)

  val io = IO(new Bundle {
    val readPorts  = Vec(readNum, new LvtReadPort(addrWidth, elemFactory))
    val writePorts = Vec(writeNum, Input(new LvtWriteNdPort(addrWidth, elemFactory)))
    val flushPort  = if (hasFlush) Some(Input(Valid(Vec(elemNum, elemFactory)))) else None
  })

  val lastValidTable = Module(new LvtLastValidTable(elemNum, readNum, writeNum, hasFlush = hasFlush))
  lastValidTable.io.readPorts.zip(io.readPorts).foreach {
    case (dst, src) =>
      dst.addr := src.addr
  }
  lastValidTable.io.writePorts.zip(io.writePorts).foreach {
    case (dst, src) =>
      dst.en   := src.en
      dst.addr := src.addr
      dst.data := DontCare
  }
  if (hasFlush) {
    lastValidTable.io.isFlush.get := io.flushPort.get.valid
  }

  val readDataBuffer = Wire(Vec(writeNum, Vec(readNum, elemFactory)))

  for (writeIdx <- 0 until writeNum) {
    for (readIdx <- 0 until readNum) {
      val lutHasFlush = writeIdx == 0 && hasFlush
      val lutRam      = Module(new LutRam(elemFactory, blankElem, elemNum, hasFlush = lutHasFlush))
      lutRam.io.writePort               := io.writePorts(writeIdx)
      lutRam.io.readPort.addr           := io.readPorts(readIdx).addr
      readDataBuffer(writeIdx)(readIdx) := lutRam.io.readPort.data
      if (lutHasFlush) {
        lutRam.io.flushPort.get := io.flushPort.get
      }
    }
  }

  io.readPorts.zipWithIndex.foreach {
    case (r, idx) =>
      r.data := readDataBuffer(lastValidTable.io.readPorts(idx).data)(idx)
  }
}
