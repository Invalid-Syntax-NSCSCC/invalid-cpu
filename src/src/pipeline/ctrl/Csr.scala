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

  // Util: view UInt as Bundle
  class BundlePassPort[T <: Bundle](port: T) extends Bundle {
    val in  = Wire(port)
    val out = Wire(port)
  }

  def viewUInt[T <: Bundle](u: UInt, bun: T): BundlePassPort[T] = {
    val passPort = new BundlePassPort(bun)
    u            := passPort.in.asUInt
    passPort.out := u.asTypeOf(bun)
    passPort
  }

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

  // CRMD 当前模式信息

  val crmd = WireDefault(csrRegs(CsrRegs.Index.crmd))

  // PRMD 例外前模式信息
  val prmd = WireDefault(csrRegs(CsrRegs.Index.prmd))

  // EUEN扩展部件使能
  val euen = WireDefault(csrRegs(CsrRegs.Index.euen))

  // ECFG 例外控制
  val ecfg = WireDefault(csrRegs(CsrRegs.Index.ecfg))

  io.csrValues.zip(csrRegs).foreach {
    case (output, reg) =>
      output := reg
  }

  // ESTAT
  val estat = viewUInt(
    csrRegs(CsrRegs.Index.estat),
    new Bundle {
      val zero1    = Bool()
      val esubcode = UInt(9.W)
      val ecode    = UInt(6.W)
      val zero2    = UInt(3.W)
      val is       = UInt(13.W)
    }
  )
  estat.in.zero1 := 0.U
  estat.in.zero2 := 0.U

}
