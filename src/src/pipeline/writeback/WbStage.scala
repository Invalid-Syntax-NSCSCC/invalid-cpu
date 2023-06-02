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

    val difftest =
      if (isDiffTest)
        Some(Output(new Bundle {
          val valid          = Bool()
          val pc             = UInt(Width.Reg.data)
          val instr          = UInt(Width.Reg.data)
          val is_TLBFILL     = Bool() // TODO
          val TLBFILL_index  = UInt(Width.Reg.addr) // TODO
          val is_CNTinst     = Bool() // TODO
          val timer_64_value = UInt(doubleWordLength.W) // TODO
          val wen            = Bool()
          val wdest          = UInt(Width.Reg.addr)
          val wdata          = UInt(Width.Reg.data)
          val csr_rstat      = Bool()
          val ld_en          = UInt(8.W)
          val ld_vaddr       = UInt(32.W)
          val ld_paddr       = UInt(32.W)
          val st_en          = UInt(8.W)
          val st_vaddr       = UInt(32.W)
          val st_paddr       = UInt(32.W)
          val st_data        = UInt(32.W)
        }))
      else None
  })
  // Always assert ready for last stage
  io.in.ready := true.B

  // Whether current instruction causes exception
  io.isExceptionValid := io.in.bits.instInfo.isValid && io.in.bits.instInfo.isExceptionValid

  // Output connection
  io.cuInstInfoPort         := io.in.bits.instInfo
  io.gprWritePort           := io.in.bits.gprWrite
  io.cuInstInfoPort.isValid := io.in.valid && io.in.bits.instInfo.isValid
  io.gprWritePort.en        := io.in.valid && io.in.bits.gprWrite.en

  // Indicate the availability in scoreboard
  io.freePort.en   := io.gprWritePort.en && io.in.valid
  io.freePort.addr := io.gprWritePort.addr

  io.csrFreePort.en   := io.in.valid && io.in.bits.instInfo.needCsr // io.in.bits.instInfo.csrWritePort.en && io.in.valid
  io.csrFreePort.addr := io.in.bits.instInfo.csrWritePort.addr

  // Diff test connection
  val exceptionVec = io.in.bits.instInfo.exceptionRecords
  io.difftest match {
    case Some(dt) =>
      dt       := DontCare
      dt.valid := RegNext(io.in.bits.instInfo.isValid && io.in.valid)
      dt.pc    := RegNext(io.in.bits.instInfo.pc)
      dt.instr := RegNext(io.in.bits.instInfo.inst)
      dt.wen   := RegNext(io.in.bits.gprWrite.en)
      dt.wdest := RegNext(io.in.bits.gprWrite.addr)
      dt.wdata := RegNext(io.in.bits.gprWrite.data)
      dt.csr_rstat := RegNext(
        io.in.bits.instInfo.inst(31, 24) === Inst._2RI14.csr_ &&
          io.in.bits.instInfo.inst(23, 10) === "h5".U
      )
      dt.ld_en    := RegNext(io.in.bits.instInfo.load.en)
      dt.ld_vaddr := RegNext(io.in.bits.instInfo.load.vaddr)
      dt.ld_paddr := RegNext(io.in.bits.instInfo.load.paddr)
      dt.st_en    := RegNext(io.in.bits.instInfo.store.en)
      dt.st_vaddr := RegNext(io.in.bits.instInfo.store.vaddr)
      dt.st_paddr := RegNext(io.in.bits.instInfo.store.paddr)
      dt.st_data  := RegNext(io.in.bits.instInfo.store.data)
    case _ =>
  }
}
