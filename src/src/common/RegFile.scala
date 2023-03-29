package common

import chisel3._
import chisel3.util._
import common.bundles._
import spec.Param.isDiffTest
import spec._

class RegFile(readNum: Int = Param.regFileReadNum) extends Module {
  val io = IO(new Bundle {
    val writePort = Input(new RfWriteNdPort)
    val readPorts = Vec(readNum, new RfReadPort)

    val difftest =
      if (isDiffTest)
        Some(Output(new Bundle {
          val gpr = Vec(Count.reg, UInt(Width.Reg.data))
        }))
      else None
  })

  // 32 bits registers of 32 number
  val regs = RegInit(VecInit(Seq.fill(Count.reg)(zeroWord)))

  // Write
  regs.zipWithIndex.foreach {
    case (reg, index) =>
      reg := Mux(
        io.writePort.en === true.B && index.U === io.writePort.addr,
        io.writePort.data,
        reg
      )
  }

  // Read
  io.readPorts.foreach { readPort =>
    readPort.data := zeroWord
    when(readPort.addr === 0.U) {
      // Always zero
      readPort.data := zeroWord
    }.elsewhen(readPort.en) {
      when(
        io.writePort.en === true.B && readPort.addr === io.writePort.addr
      ) {
        // Write fall through to read
        readPort.data := io.writePort.data
      }.otherwise {
        readPort.data := regs(readPort.addr)
      }
    }
  }

  // Diff test
  io.difftest match {
    case Some(dt) =>
      dt.gpr.zip(regs).foreach {
        case (port, reg) =>
          port := reg
      }
    case _ =>
  }
}
