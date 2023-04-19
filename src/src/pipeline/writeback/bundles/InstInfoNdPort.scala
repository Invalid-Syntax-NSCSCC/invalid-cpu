package pipeline.writeback.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import chisel3.util._
import spec._
import control.bundles.CsrWriteNdPort

class InstInfoNdPort extends Bundle {
  val pc               = UInt(Width.Reg.data)
  val inst             = UInt(Width.Reg.data)
  val exceptionRecords = Vec(CsrRegs.ExceptionIndex.width, Bool())
  val csrWritePort     = new CsrWriteNdPort

  val exeOp = UInt(Param.Width.exeOp)
  val robId = UInt(Param.robIdLength.W)
}

object InstInfoNdPort {
  val default = 0.U.asTypeOf(new InstInfoNdPort)

  def setDefault(instInfoPort: InstInfoNdPort): Unit = {
    instInfoPort.pc   := zeroWord
    instInfoPort.inst := zeroWord
    instInfoPort.exceptionRecords.foreach { record =>
      record := false.B
    }
    instInfoPort.csrWritePort := CsrWriteNdPort.default
    instInfoPort.exeOp        := 0.U
  }
}
