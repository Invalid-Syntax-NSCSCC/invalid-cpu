package frontend

import chisel3._
import chisel3.util._
import spec.Inst.{_2RI16 => Inst}
import pipeline.dispatch.bundles.InstInfoBundle
import frontend.bundles.PreDecoderResultNdPort

class PreDecoder extends Module {
  val io = IO(new Bundle {
    val in     = Input(new InstInfoBundle)
    val result = Output(new PreDecoderResultNdPort)
  })

  // fall back
  io.result := PreDecoderResultNdPort.default

  val opcode          = io.in.inst(31, 26)
  val imm26Sext       = Cat(io.in.inst(9, 0), io.in.inst(25, 10)).asSInt
  val imm26SextShift2 = Wire(SInt(spec.Width.Reg.data))
  imm26SextShift2 := imm26Sext << 2

  switch(opcode) {
    is(Inst.b_, Inst.bl) {
      io.result.isUnconditionalJump := true.B
      io.result.jumpTargetAddr      := imm26SextShift2.asUInt
    }
    is(Inst.jirl) {
      io.result.isUnconditionalJump := true.B
      io.result.isRegJump           := true.B
    }
  }
}
