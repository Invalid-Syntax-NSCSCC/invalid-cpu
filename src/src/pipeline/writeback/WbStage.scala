package pipeline.writeback

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.mem.MemResNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Param.isDiffTest
import spec._

class WbNdPort extends Bundle {
  val gprWrite = new RfWriteNdPort
  val instInfo = new InstInfoNdPort
}

class WbStage extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new WbNdPort))

    // `WbStage` -> `Cu` NO delay
    val gprWritePort = Output(new RfWriteNdPort)

    // Scoreboard
    val freePort    = Output(new ScoreboardChangeNdPort)
    val csrFreePort = Output(new ScoreboardChangeNdPort)

    // `AddrTransStage` -> `WbStage` -> `Cu`  NO delay
    val cuInstInfoPort = Output(new InstInfoNdPort)

    // `WbStage` -> `Cu` NO delay
    val isExceptionValid = Output(Bool())

    // `Csr` -> `WbStage`
    val hasInterrupt = Input(Bool())

    val difftest =
      if (isDiffTest)
        Some(Output(new Bundle {
          val valid         = Bool()
          val pc            = UInt(Width.Reg.data)
          val instr         = UInt(Width.Reg.data)
          val is_TLBFILL    = Bool() // TODO
          val TLBFILL_index = UInt(Width.Reg.addr) // TODO
          val wen           = Bool()
          val wdest         = UInt(Width.Reg.addr)
          val wdata         = UInt(Width.Reg.data)
          val csr_rstat     = Bool()
          val ld_en         = UInt(8.W)
          val ld_vaddr      = UInt(32.W)
          val ld_paddr      = UInt(32.W)
          val st_en         = UInt(8.W)
          val st_vaddr      = UInt(32.W)
          val st_paddr      = UInt(32.W)
          val st_data       = UInt(32.W)
        }))
      else None
  })
  // Always assert ready for last stage
  io.in.ready := true.B

  val inBits = WireDefault(io.in.bits)

  val hasInterruptReg = RegInit(false.B)
  when(io.hasInterrupt) {
    when(io.in.valid) {
      inBits.instInfo.exceptionRecords(Csr.ExceptionIndex.int) := true.B
      inBits.instInfo.isExceptionValid                         := true.B
    }.otherwise {
      hasInterruptReg := true.B
    }
  }.elsewhen(hasInterruptReg && io.in.valid) {
    hasInterruptReg                                          := false.B
    inBits.instInfo.exceptionRecords(Csr.ExceptionIndex.int) := true.B
    inBits.instInfo.isExceptionValid                         := true.B
  }

  // Whether current instruction causes exception
  io.isExceptionValid := inBits.instInfo.isValid && inBits.instInfo.isExceptionValid

  // Output connection
  io.cuInstInfoPort         := inBits.instInfo
  io.gprWritePort           := inBits.gprWrite
  io.cuInstInfoPort.isValid := io.in.valid && inBits.instInfo.isValid
  io.gprWritePort.en        := io.in.valid && inBits.gprWrite.en

  // Indicate the availability in scoreboard
  io.freePort.en   := io.gprWritePort.en && io.in.valid
  io.freePort.addr := io.gprWritePort.addr

  io.csrFreePort.en   := io.in.valid && inBits.instInfo.needCsr
  io.csrFreePort.addr := inBits.instInfo.csrWritePort.addr

  // Diff test connection
  val exceptionVec = inBits.instInfo.exceptionRecords
  io.difftest match {
    case Some(dt) =>
      dt       := DontCare
      dt.valid := RegNext(inBits.instInfo.isValid && io.in.valid)
      dt.pc    := RegNext(inBits.instInfo.pc)
      dt.instr := RegNext(inBits.instInfo.inst)
      dt.wen   := RegNext(inBits.gprWrite.en)
      dt.wdest := RegNext(inBits.gprWrite.addr)
      dt.wdata := RegNext(inBits.gprWrite.data)
      dt.csr_rstat := RegNext(
        inBits.instInfo.inst(31, 24) === Inst._2RI14.csr_ &&
          inBits.instInfo.inst(23, 10) === "h5".U
      )
      dt.ld_en    := RegNext(inBits.instInfo.load.en)
      dt.ld_vaddr := RegNext(inBits.instInfo.load.vaddr)
      dt.ld_paddr := RegNext(inBits.instInfo.load.paddr)
      dt.st_en    := RegNext(inBits.instInfo.store.en)
      dt.st_vaddr := RegNext(inBits.instInfo.store.vaddr)
      dt.st_paddr := RegNext(inBits.instInfo.store.paddr)
      dt.st_data  := RegNext(inBits.instInfo.store.data)
    case _ =>
  }
}
