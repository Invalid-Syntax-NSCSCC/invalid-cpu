package pipeline.complex.commit

import chisel3._
import chisel3.util._
import common.bundles.RfWriteNdPort
import control.enums.ExceptionPos
import pipeline.common.bundles.{InstInfoNdPort, PcInstBundle}
import pmu.bundles.PmuBranchPredictNdPort
import spec.Param.isDiffTest
import spec._

class WbNdPort extends Bundle {
  val gprWrite = new RfWriteNdPort
  val instInfo = new InstInfoNdPort
}

object WbNdPort {
  def default = 0.U.asTypeOf(new WbNdPort)
}

class CommitNdPort extends Bundle {
  val gprWrite  = new RfWriteNdPort
  val instInfo  = new InstInfoNdPort
  val fetchInfo = new PcInstBundle
}

object CommitNdPort {
  def default = 0.U.asTypeOf(new CommitNdPort)
}

class CommitStage(
  commitNum: Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {
    val ins = Vec(commitNum, Flipped(Decoupled(new CommitNdPort)))

    // `CommitStage` -> `Cu` NO delay
    val gprWritePorts   = Output(Vec(commitNum, new RfWriteNdPort))
    val cuInstInfoPorts = Output(Vec(commitNum, new InstInfoNdPort))
    val majorPc         = Output(UInt(Width.Reg.data))

    val pmu_branchInfo = if (Param.usePmu) Some(Output(new PmuBranchPredictNdPort)) else None

    val difftest =
      if (isDiffTest)
        Some(Output(new Bundle {
          val valid         = Bool()
          val pc            = UInt(Width.Reg.data)
          val instr         = UInt(Width.Reg.data)
          val is_TLBFILL    = Bool()
          val TLBFILL_index = UInt(Width.Reg.addr)
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

          val cnt_inst = Bool()
          val timer_64 = UInt(64.W)

          val valid_1 = Bool()
          val pc_1    = UInt(Width.Reg.data)
          val instr_1 = UInt(Width.Reg.data)
          val wen_1   = Bool()
          val wdest_1 = UInt(Width.Reg.addr)
          val wdata_1 = UInt(Width.Reg.data)
        }))
      else None
  })

  io.ins.foreach(_.ready := true.B)

  val inBits = WireDefault(VecInit(io.ins.map(_.bits)))

  // Output connection
  io.cuInstInfoPorts.lazyZip(io.gprWritePorts).lazyZip(io.ins).lazyZip(inBits).foreach {
    case (dstInstInfo, dstGprWrite, in, inBit) =>
      dstInstInfo         := inBit.instInfo
      dstInstInfo.isValid := in.valid && in.ready && inBit.instInfo.isValid
      dstGprWrite         := inBit.gprWrite
      dstGprWrite.en      := in.valid && in.ready && inBit.gprWrite.en
  }
  io.majorPc := inBits.head.fetchInfo.pcAddr

  io.pmu_branchInfo match {
    case None =>
    case Some(br) =>
      br.isBranch := inBits.head.instInfo.ftqCommitInfo.isBranch &&
        inBits.head.instInfo.isValid &&
        io.ins.head.ready &&
        io.ins.head.valid
      br.isRedirect          := inBits.head.instInfo.ftqCommitInfo.isRedirect
      br.branchType          := inBits.head.instInfo.ftqCommitInfo.branchType
      br.directionMispredict := inBits.head.instInfo.ftqCommitInfo.directionMispredict.get
      br.targetMispredict    := inBits.head.instInfo.ftqCommitInfo.targetMispredict.get
  }

  // Diff test connection
  io.difftest match {
    case Some(dt) =>
      dt.valid := RegNext(inBits(0).instInfo.isValid && io.ins(0).valid && io.ins(0).ready, false.B) // && nextCommit)
      dt.pc    := RegNext(inBits(0).fetchInfo.pcAddr, 0.U)
      dt.instr := RegNext(inBits(0).fetchInfo.inst, 0.U)
      dt.wen   := RegNext(inBits(0).gprWrite.en, false.B)
      dt.wdest := RegNext(inBits(0).gprWrite.addr, 0.U)
      dt.wdata := RegNext(inBits(0).gprWrite.data, 0.U)
      dt.csr_rstat := RegNext(
        inBits(0).fetchInfo.inst(31, 24) === Inst._2RI14.csr_ &&
          inBits(0).fetchInfo.inst(23, 10) === "h5".U,
        false.B
      ) && io.ins(0).valid && io.ins(0).ready
      dt.ld_en    := RegNext(inBits(0).instInfo.load.get.en, false.B)
      dt.ld_vaddr := RegNext(inBits(0).instInfo.load.get.vaddr, 0.U)
      dt.ld_paddr := RegNext(inBits(0).instInfo.load.get.paddr, 0.U)
      dt.st_en    := RegNext(inBits(0).instInfo.store.get.en, false.B)
      dt.st_vaddr := RegNext(inBits(0).instInfo.store.get.vaddr, 0.U)
      dt.st_paddr := RegNext(inBits(0).instInfo.store.get.paddr, 0.U)
      dt.st_data  := RegNext(inBits(0).instInfo.store.get.data, 0.U)

      dt.cnt_inst := RegNext(inBits(0).instInfo.timerInfo.get.isCnt, false.B)
      dt.timer_64 := RegNext(inBits(0).instInfo.timerInfo.get.timer64, 0.U)

      dt.valid_1 := false.B
      dt.instr_1 := DontCare
      dt.pc_1    := DontCare
      dt.wen_1   := DontCare
      dt.wdest_1 := DontCare
      dt.wdata_1 := DontCare

      if (commitNum == 2) {
        dt.valid_1 := RegNext(inBits(1).instInfo.isValid && io.ins(1).valid && io.ins(1).ready, false.B)
        dt.instr_1 := RegNext(inBits(1).fetchInfo.inst, 0.U)
        dt.pc_1    := RegNext(inBits(1).fetchInfo.pcAddr, 0.U)
        dt.wen_1   := RegNext(inBits(1).gprWrite.en, false.B)
        dt.wdest_1 := RegNext(inBits(1).gprWrite.addr, 0.U)
        dt.wdata_1 := RegNext(inBits(1).gprWrite.data, 0.U)
      }

      dt.is_TLBFILL := RegNext(
        inBits(0).instInfo.tlbFill.get.valid && inBits(0).instInfo.exceptionPos === ExceptionPos.none,
        false.B
      )
      dt.TLBFILL_index := RegNext(inBits(0).instInfo.tlbFill.get.fillIndex, 0.U)
    case _ =>
  }
}
