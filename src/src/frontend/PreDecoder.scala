package frontend

import chisel3._
import chisel3.util._
import spec.Inst.{_2RI16 => Inst}
import frontend.bundles.PreDecoderResultNdPort
import spec.Param.BPU.BranchType
import spec.Width

class PreDecoder extends Module {
  val io = IO(new Bundle {
    val pc     = Input(UInt(Width.Reg.data))
    val inst   = Input(UInt(Width.Reg.data))
    val result = Output(new PreDecoderResultNdPort)
  })

  // fall back
  io.result := PreDecoderResultNdPort.default

  val opcode          = io.inst(31, 26)
  val rj              = WireDefault(io.inst(9, 5))
  val rd              = WireDefault(io.inst(4, 0))
  val imm26Sext       = Cat(io.inst(9, 0), io.inst(25, 10)).asSInt
  val imm26SextShift2 = Wire(SInt(spec.Width.Reg.data))
  imm26SextShift2 := imm26Sext << 2

  switch(opcode) {
    is(Inst.b_) {
      io.result.isImmJump      := true.B
      io.result.jumpTargetAddr := imm26SextShift2.asUInt + io.pc
    }
    is(Inst.bl) {
      io.result.isImmJump      := true.B
      io.result.jumpTargetAddr := imm26SextShift2.asUInt + io.pc
      io.result.branchType     := BranchType.call
    }
    is(Inst.jirl) {
      io.result.isImmJump := false.B
      io.result.branchType := Mux(
        rd === 0.U && rj === 1.U,
        BranchType.ret,
        Mux(
          rd === 1.U,
          BranchType.call,
          BranchType.uncond
        )
      )
    }
  }
}
