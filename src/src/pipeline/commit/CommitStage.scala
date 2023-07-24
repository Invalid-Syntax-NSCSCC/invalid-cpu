package pipeline.commit

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import common.bundles.RfWriteNdPort
import control.enums.ExceptionPos
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import spec.Param.isDiffTest
import spec._

class WbNdPort extends Bundle {
  val gprWrite = new RfWriteNdPort
  val instInfo = new InstInfoNdPort
}

object WbNdPort {
  def default = 0.U.asTypeOf(new WbNdPort)
}

class CommitStage(
  commitNum: Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {
    val ins = Vec(commitNum, Flipped(Decoupled(new WbNdPort)))

    // `CommitStage` -> `Cu` NO delay
    val gprWritePorts = Output(Vec(commitNum, new RfWriteNdPort))

    // `AddrTransStage` -> `CommitStage` -> `Cu` NO delay
    val cuInstInfoPorts = Output(Vec(commitNum, new InstInfoNdPort))

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

  // Diff test connection
  io.difftest match {
    case Some(dt) =>
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
      dt.ld_en    := RegNext(inBits(0).instInfo.load.get.en)
      dt.ld_vaddr := RegNext(inBits(0).instInfo.load.get.vaddr)
      dt.ld_paddr := RegNext(inBits(0).instInfo.load.get.paddr)
      dt.st_en    := RegNext(inBits(0).instInfo.store.get.en)
      dt.st_vaddr := RegNext(inBits(0).instInfo.store.get.vaddr)
      dt.st_paddr := RegNext(inBits(0).instInfo.store.get.paddr)
      dt.st_data  := RegNext(inBits(0).instInfo.store.get.data)

      dt.cnt_inst := RegNext(inBits(0).instInfo.timerInfo.get.isCnt)
      dt.timer_64 := RegNext(inBits(0).instInfo.timerInfo.get.timer64)

      dt.valid_1 := false.B
      dt.instr_1 := DontCare
      dt.pc_1    := DontCare
      dt.wen_1   := DontCare
      dt.wdest_1 := DontCare
      dt.wdata_1 := DontCare

      if (Param.isShowBranchNum) {
        val branchCounter                    = RegInit(zeroWord)
        val branchPredictFailedCounter       = RegInit(zeroWord)
        val unconditionalBranchCounter       = dontTouch(RegInit(zeroWord))
        val unconditionalBranchFailedCounter = dontTouch(RegInit(zeroWord))
        val condBranchCounter                = dontTouch(RegInit(zeroWord))
        val condBranchFailedCounter          = dontTouch(RegInit(zeroWord))
        val callBranchCounter                = dontTouch(RegInit(zeroWord))
        val callBranchFailedCounter          = dontTouch(RegInit(zeroWord))
        val retBranchCounter                 = dontTouch(RegInit(zeroWord))
        val retBranchFailedCounter           = dontTouch(RegInit(zeroWord))
        dt.pc_1    := branchCounter
        dt.wdata_1 := branchPredictFailedCounter
        when(inBits(0).instInfo.isValid && io.ins(0).valid && io.ins(0).ready) {
          // all branch
          when(inBits(0).instInfo.ftqCommitInfo.isBranch) {
            branchCounter := branchCounter + 1.U
            when(inBits(0).instInfo.ftqCommitInfo.isRedirect) {
              branchPredictFailedCounter := branchPredictFailedCounter + 1.U
            }

            // uncond
            when(inBits(0).instInfo.ftqCommitInfo.branchType === Param.BPU.BranchType.uncond) {
              unconditionalBranchCounter := unconditionalBranchCounter + 1.U
              when(inBits(0).instInfo.ftqCommitInfo.isRedirect) {
                unconditionalBranchFailedCounter := unconditionalBranchFailedCounter + 1.U
              }
            }

            // cond
            when(inBits(0).instInfo.ftqCommitInfo.branchType === Param.BPU.BranchType.cond) {
              condBranchCounter := condBranchCounter + 1.U
              when(inBits(0).instInfo.ftqCommitInfo.isRedirect) {
                condBranchFailedCounter := condBranchFailedCounter + 1.U
              }
            }

            // call
            when(inBits(0).instInfo.ftqCommitInfo.branchType === Param.BPU.BranchType.call) {
              callBranchCounter := callBranchCounter + 1.U
              when(inBits(0).instInfo.ftqCommitInfo.isRedirect) {
                callBranchFailedCounter := callBranchFailedCounter + 1.U
              }
            }

            // ret
            when(inBits(0).instInfo.ftqCommitInfo.branchType === Param.BPU.BranchType.ret) {
              retBranchCounter := retBranchCounter + 1.U
              when(inBits(0).instInfo.ftqCommitInfo.isRedirect) {
                retBranchFailedCounter := retBranchFailedCounter + 1.U
              }
            }
          }

        }
      }

      if (commitNum == 2) {
        dt.valid_1 := RegNext(inBits(1).instInfo.isValid && io.ins(1).valid && io.ins(1).ready)
        dt.instr_1 := RegNext(inBits(1).instInfo.inst)
        dt.pc_1    := RegNext(inBits(1).instInfo.pc)
        dt.wen_1   := RegNext(inBits(1).gprWrite.en)
        dt.wdest_1 := RegNext(inBits(1).gprWrite.addr)
        dt.wdata_1 := RegNext(inBits(1).gprWrite.data)
      }

      dt.is_TLBFILL := RegNext(
        inBits(0).instInfo.tlbFill.get.valid && inBits(0).instInfo.exceptionPos === ExceptionPos.none
      )
      dt.TLBFILL_index := RegNext(inBits(0).instInfo.tlbFill.get.fillIndex)
    case _ =>
  }
}
