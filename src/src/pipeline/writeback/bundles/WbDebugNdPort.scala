package pipeline.writeback.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import spec._

class WbDebugNdPort extends Bundle {
  val pc               = UInt(Width.Reg.data)
  val inst             = UInt(Width.Reg.data)
  val exceptionRecords = Vec(CsrRegs.ExceptionIndex.width, Bool())
}

object WbDebugNdPort {
  // val default = (new WbDebugNdPort).Lit(
  //   _.pc -> zeroWord,
  //   _.inst -> zeroWord,
  //   _.exceptionRecord -> VecInit(Seq.fill(CsrRegs.ExceptionIndex.width)(false.B))
  // )
  def setDefault(wbDebugPort: WbDebugNdPort): Unit = {
    wbDebugPort.pc   := zeroWord
    wbDebugPort.inst := zeroWord
    wbDebugPort.exceptionRecords.foreach { record =>
      record := false.B
    }
  }
}
