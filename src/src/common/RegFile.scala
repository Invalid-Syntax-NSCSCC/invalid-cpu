package common

import chisel3._
import common.bundles._
import spec.Param.isDiffTest
import spec._

class RegFile(
  issueNum: Int = Param.issueInstInfoMaxNum,
  readNum:  Int = Param.regFileReadNum,
  writeNum: Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {
    val writePorts = Input(Vec(writeNum, new RfWriteNdPort))
    val readPorts  = Vec(issueNum, Vec(readNum, new RfReadPort))

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
  // larger index write first
  regs.zipWithIndex.foreach {
    case (reg, index) =>
      io.writePorts.foreach { write =>
        when(write.en && index.U === write.addr) {
          reg := write.data
        }
      }
  }

  // Read
  io.readPorts.foreach { readPorts =>
    readPorts.foreach { readPort =>
      readPort.data := zeroWord
      when(readPort.addr === 0.U) {
        // Always zero
        readPort.data := zeroWord
      }.elsewhen(readPort.en) {
        readPort.data := regs(readPort.addr)
        io.writePorts.foreach { write =>
          when(write.en && write.addr === readPort.addr) {
            readPort.data := write.data
          }
        }
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
