package pipeline.writeback.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._

class InstInfoNdPort extends Bundle {
  val pc               = UInt(Width.Reg.data)
  val inst             = UInt(Width.Reg.data)
  val exceptionRecords = Vec(CsrRegs.ExceptionIndex.width, Bool())
}

object InstInfoNdPort {
  def setDefault(wbDebugPort: InstInfoNdPort): Unit = {
    wbDebugPort.pc   := zeroWord
    wbDebugPort.inst := zeroWord
    wbDebugPort.exceptionRecords.foreach { record =>
      record := false.B
    }
  }
}
