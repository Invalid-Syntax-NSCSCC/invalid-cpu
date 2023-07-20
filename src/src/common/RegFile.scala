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

    val regfileDatas = Output(Vec(Count.reg, UInt(spec.Width.Reg.data)))
  })

  // 32 bits registers of 32 number
  val regs = RegInit(VecInit(Seq.fill(Count.reg)(zeroWord)))

  // Write
  // larger index write first
  regs.zipWithIndex.foreach {
    case (reg, index) =>
      if (index != 0) {
        io.writePorts.foreach { write =>
          when(write.en && index.U === write.addr) {
            reg := write.data
          }
        }
      }
  }

  io.regfileDatas.zip(regs).foreach {
    case (port, reg) =>
      port := reg
  }
}
