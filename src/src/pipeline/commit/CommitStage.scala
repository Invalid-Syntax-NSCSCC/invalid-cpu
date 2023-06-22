package pipeline.commit

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.mem.MemResNdPort
import pipeline.commit.bundles.InstInfoNdPort
import spec.Param.isDiffTest
import spec._
import chisel3.experimental.BundleLiterals._
import control.bundles.CsrValuePort
import control.enums.ExceptionPos

class WbNdPort extends Bundle {
  val gprWrite = new RfWriteNdPort
  val instInfo = new InstInfoNdPort
}

object WbNdPort {
  def default = (new WbNdPort).Lit(
    _.gprWrite -> RfWriteNdPort.default,
    _.instInfo -> InstInfoNdPort.default
  )
}

class CommitStage(
  commitNum: Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {
    val ins = Vec(commitNum, Flipped(Decoupled(new WbNdPort)))

    // `CommitStage` -> `Cu` NO delay
    val gprWritePorts = Output(Vec(commitNum, new RfWriteNdPort))

    val csrFreePort = Output(new ScoreboardChangeNdPort)

    // `AddrTransStage` -> `CommitStage` -> `Cu` NO delay
    val cuInstInfoPorts = Output(Vec(commitNum, new InstInfoNdPort))

    // `CommitStage` -> `Cu` NO delay
    val isExceptionValid = Output(Bool())

    val csrValues = Input(new CsrValuePort)

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

  io.ins.foreach(_.ready := true.B)

  val inBits = WireDefault(VecInit(io.ins.map(_.bits)))

  // Whether current instruction causes exception
  io.isExceptionValid := inBits.map { inBit =>
    inBit.instInfo.isValid && (inBit.instInfo.exceptionPos =/= ExceptionPos.none)
  }
    .reduce(_ || _) // inBits.instInfo.isValid && inBits.instInfo.isExceptionValid

  // Output connection
  io.cuInstInfoPorts.lazyZip(io.gprWritePorts).lazyZip(io.ins).lazyZip(inBits).foreach {
    case (dstInstInfo, dstGprWrite, in, inBit) =>
      dstInstInfo         := inBit.instInfo
      dstInstInfo.isValid := in.valid && in.ready && inBit.instInfo.isValid
      dstGprWrite         := inBit.gprWrite
      dstGprWrite.en      := in.valid && in.ready && inBit.gprWrite.en
  }

  // Indicate the availability in scoreboard

  io.csrFreePort.en := io.ins
    .zip(inBits)
    .map {
      case (in, inBit) =>
        in.valid && in.ready && inBit.instInfo.needCsr
    }
    .reduce(_ || _)
  io.csrFreePort.addr := DontCare

  // Diff test connection
  io.difftest match {
    case Some(dt) =>
      dt       := DontCare
      dt.valid := RegNext(inBits(0).instInfo.isValid && io.ins(0).valid && io.ins(0).ready) // && nextCommit)
      dt.pc    := RegNext(inBits(0).instInfo.pc)
      dt.instr := RegNext(inBits(0).instInfo.inst)
      dt.wen   := RegNext(inBits(0).gprWrite.en)
      dt.wdest := RegNext(inBits(0).gprWrite.addr)
      dt.wdata := RegNext(inBits(0).gprWrite.data)
      dt.csr_rstat := RegNext(
        inBits(0).instInfo.inst(31, 24) === Inst._2RI14.csr_ &&
          inBits(0).instInfo.inst(23, 10) === "h5".U
      ) && io.ins(0).valid && io.ins(0).ready
      dt.ld_en    := RegNext(inBits(0).instInfo.load.en)
      dt.ld_vaddr := RegNext(inBits(0).instInfo.load.vaddr)
      dt.ld_paddr := RegNext(inBits(0).instInfo.load.paddr)
      dt.st_en    := RegNext(inBits(0).instInfo.store.en)
      dt.st_vaddr := RegNext(inBits(0).instInfo.store.vaddr)
      dt.st_paddr := RegNext(inBits(0).instInfo.store.paddr)
      dt.st_data  := RegNext(inBits(0).instInfo.store.data)
    case _ =>
  }
}
