package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort
import spec.PipelineStageIndex
import pipeline.ctrl.bundles.CsrWriteNdPort

class Csr(writeNum: Int = Param.csrRegsWriteNum) extends Module {
  val io = IO(new Bundle {
    val writePorts = Input(Vec(writeNum, new CsrWriteNdPort))
    val csrValues  = Output(Vec(Count.csrReg, UInt(Width.Reg.data)))
  })

  val csrRegs = RegInit(VecInit(Seq.fill(Count.csrReg)(zeroWord)))

  io.writePorts.foreach { writePort =>
    when(writePort.en) {
      csrRegs(writePort.addr) := writePort.data
      // 保留域
      switch(writePort.addr) {
        is(CsrRegs.Index.estat) {
          csrRegs(writePort.addr) := Cat(false.B, writePort.data(30, 16), 0.U(3.W), writePort.data(12, 0))
        }
      }
    }
  }

  io.csrValues.zip(csrRegs).foreach {
    case (output, reg) =>
      output := reg
  }

  // ESTAT
  val estatRegValue = WireDefault(csrRegs(CsrRegs.Index.estat))
  val estat = new Bundle {
    val is       = estatRegValue(12, 0)
    val ecode    = estatRegValue(21, 16)
    val esubcode = estatRegValue(30, 22)
  }

}
