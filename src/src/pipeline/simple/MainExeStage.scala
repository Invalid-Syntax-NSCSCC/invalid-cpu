package pipeline.simple

import chisel3._
import chisel3.util._
import common.BaseStage
import common.bundles._
import common.enums.ReadWriteSel
import control.bundles._
import control.csrBundles._
import control.enums.ExceptionPos
import frontend.bundles._
import memory.bundles.CacheMaintenanceControlNdPort
import pipeline.common.enums.CacheMaintenanceTargetType
import pipeline.simple.bundles.{InstInfoNdPort, MainExeBranchInfoBundle, RegWakeUpNdPort}
import pipeline.simple.execution.Alu
import spec.ExeInst.OpBundle
import spec._

import scala.collection.immutable
import common.NoSavedInBaseStage

class ExeNdPort extends Bundle {
  // Operands
  val leftOperand  = UInt(Width.Reg.data)
  val rightOperand = UInt(Width.Reg.data)

  // Branch jump addr
  val jumpBranchAddr    = UInt(Width.Reg.data)
  def loadStoreImm      = jumpBranchAddr
  def csrAddr           = jumpBranchAddr
  def tlbInvalidateInst = jumpBranchAddr
  def code              = jumpBranchAddr

  // GPR write (writeback)
  val gprWritePort = new RfAccessInfoNdPort

  val instInfo = new InstInfoNdPort
}

object ExeNdPort {
  def default = 0.U.asTypeOf(new ExeNdPort)
}

class MainExeNdPort extends ExeNdPort {

  val branchInfo = new MainExeBranchInfoBundle
}

object MainExeNdPort {
  def default = 0.U.asTypeOf(new MainExeNdPort)
}

class ExePeerPort extends Bundle {
  val csr = Input(new Bundle {
    val llbctl = new LlbctlBundle
    val era    = new EraBundle
  })
  val dbarFinish = Input(Bool())

  val branchSetPort     = Output(new BackendRedirectPcNdPort)
  val csrWriteStorePort = Output(Valid(new CsrWriteNdPort))

  // `Exe` <-> `StableCounter`
  val stableCounterReadPort = Flipped(new StableCounterReadPort)

  val csrReadPort = Flipped(new CsrReadPort)
  val feedbackFtq = Output(new ExeFtqFeedBackNdPort)

  // val robQueryPcPort = Flipped(new RobQueryPcPort)

  val regWakeUpPort = Output(new RegWakeUpNdPort)

  val commitFtqPort = new CommitFtqTrainNdPort

}

class MainExeStage
    extends NoSavedInBaseStage(
      new MainExeNdPort,
      new AddrTransNdPort,
      MainExeNdPort.default,
      Some(new ExePeerPort)
    ) {
  val out      = Wire(new AddrTransNdPort)
  val outValid = Wire(Bool())
  resultOutReg.valid := outValid
  resultOutReg.bits  := out
  val peer = io.peer.get

  val commitFtqInfo = out.commitFtqPort
  when(out.wb.instInfo.exceptionPos =/= ExceptionPos.none || !outValid) {
    commitFtqInfo.isTrainValid := false.B
  }

  peer.commitFtqPort := (if (Param.exeFeedBackFtqDelay) RegNext(commitFtqInfo)
                         else commitFtqInfo)

  // Fallback
  // ALU module
  val alu = Module(new Alu(onlySupportBranch = Param.isUse3Unit))

  val isComputed = Wire(Bool())
  val selectedIn = io.in.bits
  isComputed                      := (if (Param.isUse3Unit) true.B else alu.io.outputValid)
  out                             := DontCare
  out.cacheMaintenance.control    := CacheMaintenanceControlNdPort.default
  out.isAtomicStore               := false.B
  out.tlbMaintenance.isRead       := false.B
  out.tlbMaintenance.isSearch     := false.B
  out.tlbMaintenance.isFill       := false.B
  out.tlbMaintenance.isWrite      := false.B
  out.tlbMaintenance.isInvalidate := false.B
  out.wb.instInfo                 := selectedIn.instInfo
  out.wb.gprWrite.en              := selectedIn.gprWritePort.en
  out.wb.gprWrite.addr            := selectedIn.gprWritePort.addr
  outValid                        := inReady && isComputed && selectedIn.instInfo.isValid && io.in.valid
  io.in.ready                     := inReady && isComputed

  // memory

  val isDbarBlockingReg = RegInit(false.B)
  // dbar start
  when(selectedIn.instInfo.isValid && selectedIn.instInfo.exeOp === OpBundle.dbar && outValid) {
    isDbarBlockingReg := true.B
  }
  // dbar execute and finish
  when(isDbarBlockingReg) {
    isComputed := false.B
    when(io.peer.get.dbarFinish) {
      isDbarBlockingReg := false.B
    }
  }

  // Generate address
  val isAddrNotAligned   = WireDefault(false.B)
  val loadStoreAddr      = selectedIn.leftOperand + selectedIn.loadStoreImm
  val maskEncode         = loadStoreAddr(1, 0)
  val isAtomicLoad       = selectedIn.instInfo.exeOp === OpBundle.ll
  val isAtomicStoreValid = io.peer.get.csr.llbctl.rollb && selectedIn.instInfo.exeOp === OpBundle.sc

  val isSimpleMemory  = selectedIn.instInfo.exeOp.sel === OpBundle.sel_simpleMemory
  val isComplexMemory = selectedIn.instInfo.exeOp.sel === OpBundle.sel_complexMemory

  val isWrite =
    (VecInit(OpBundle.st_b.subOp, OpBundle.st_h.subOp, OpBundle.st_w.subOp)
      .contains(selectedIn.instInfo.exeOp.subOp) && isSimpleMemory) || isAtomicStoreValid
  val isValidLoadStore = (isSimpleMemory || isAtomicLoad || isAtomicStoreValid) && !isAddrNotAligned

  // default : simple memory
  switch(selectedIn.instInfo.exeOp.subOp) {
    is(OpBundle.ld_b.subOp, OpBundle.ld_bu.subOp, OpBundle.st_b.subOp) {
      out.memRequest.mask := Mux(
        maskEncode(1),
        Mux(maskEncode(0), "b1000".U, "b0100".U),
        Mux(maskEncode(0), "b0010".U, "b0001".U)
      )
    }
    is(OpBundle.ld_h.subOp, OpBundle.ld_hu.subOp, OpBundle.st_h.subOp) {
      when(maskEncode(0)) {
        isAddrNotAligned := true.B && isSimpleMemory
      }
      out.memRequest.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
    }
    is(OpBundle.ld_w.subOp, OpBundle.st_w.subOp) {
      isAddrNotAligned    := maskEncode.orR && isSimpleMemory
      out.memRequest.mask := "b1111".U
    }
  }

  // ll , sc
  when(isComplexMemory) {
    switch(selectedIn.instInfo.exeOp.subOp) {
      is(OpBundle.ll.subOp, OpBundle.sc.subOp) {
        isAddrNotAligned    := maskEncode.orR
        out.memRequest.mask := "b1111".U
      }
    }
  }

  // store data
  out.memRequest.write.data := selectedIn.rightOperand
  when(isSimpleMemory) {
    switch(selectedIn.instInfo.exeOp.subOp) {
      is(OpBundle.st_b.subOp) {
        out.memRequest.write.data := Cat(
          Seq.fill(wordLength / byteLength)(selectedIn.rightOperand(byteLength - 1, 0))
        )
      }
      is(OpBundle.st_h.subOp) {
        out.memRequest.write.data := Cat(Seq.fill(2)(selectedIn.rightOperand(wordLength / 2 - 1, 0)))
      }
    }
  }

  out.memRequest.isValid := isValidLoadStore
  out.memRequest.addr    := loadStoreAddr
  out.memRequest.read.isUnsigned := VecInit(
    OpBundle.ld_bu.subOp,
    OpBundle.ld_hu.subOp
  ).contains(selectedIn.instInfo.exeOp.subOp) && isSimpleMemory
  out.memRequest.rw := Mux(isWrite, ReadWriteSel.write, ReadWriteSel.read)
  out.isAtomicStore := selectedIn.instInfo.exeOp === OpBundle.sc

  when(isValidLoadStore) {
    out.wb.instInfo.forbidParallelCommit := true.B
  }

  // Handle exception
  when(selectedIn.instInfo.exceptionPos === ExceptionPos.none && isAddrNotAligned) {
    out.wb.instInfo.exceptionPos    := ExceptionPos.backend
    out.wb.instInfo.exceptionRecord := Csr.ExceptionIndex.ale
  }

  // Handle TLB maintenance
  out.tlbMaintenance.registerAsid   := selectedIn.leftOperand(9, 0)
  out.tlbMaintenance.virtAddr       := selectedIn.rightOperand
  out.tlbMaintenance.invalidateInst := selectedIn.tlbInvalidateInst
  out.tlbMaintenance.isFill         := selectedIn.instInfo.exeOp === OpBundle.tlbfill
  out.tlbMaintenance.isRead         := selectedIn.instInfo.exeOp === OpBundle.tlbrd
  out.tlbMaintenance.isSearch       := selectedIn.instInfo.exeOp === OpBundle.tlbsrch
  out.tlbMaintenance.isWrite        := selectedIn.instInfo.exeOp === OpBundle.tlbwr
  out.tlbMaintenance.isInvalidate   := selectedIn.instInfo.exeOp === OpBundle.invtlb

  // Cache maintenance
  val cacopAddr = selectedIn.leftOperand + selectedIn.rightOperand
  val isCacop   = selectedIn.instInfo.exeOp === OpBundle.cacop
  when(isCacop) {
    out.memRequest.addr                  := cacopAddr
    out.wb.instInfo.forbidParallelCommit := true.B

    switch(selectedIn.code(2, 0)) {
      is(0.U) {
        out.cacheMaintenance.target            := CacheMaintenanceTargetType.inst
        out.cacheMaintenance.control.isL1Valid := true.B
      }
      is(1.U) {
        out.cacheMaintenance.target            := CacheMaintenanceTargetType.data
        out.cacheMaintenance.control.isL1Valid := true.B
      }
      is(2.U) {
        out.cacheMaintenance.control.isL2Valid := true.B
      }
    }

    switch(selectedIn.code(4, 3)) {
      is(0.U) {
        out.cacheMaintenance.control.isInit := true.B
      }
      is(1.U) {
        out.cacheMaintenance.control.isCoherentByIndex := true.B
      }
      is(2.U) {
        out.cacheMaintenance.control.isCoherentByHit := true.B
      }
    }
  }

  // wake up

  val disableWakeUp = out.wb.instInfo.exceptionPos === ExceptionPos.none && VecInit(
    OpBundle.sel_simpleMemory,
    OpBundle.sel_complexMemory
  ).contains(selectedIn.instInfo.exeOp.sel)
  val wakeUp = io.peer.get.regWakeUpPort
  wakeUp.en    := outValid && selectedIn.gprWritePort.en && !disableWakeUp
  wakeUp.addr  := selectedIn.gprWritePort.addr
  wakeUp.data  := out.wb.gprWrite.data
  wakeUp.robId := selectedIn.instInfo.robId

  // Difftest
  if (Param.isDiffTest) {
    out.wb.instInfo.load.get.en := Mux(
      isAddrNotAligned,
      0.U,
      Cat(
        0.U(2.W),
        selectedIn.instInfo.exeOp === OpBundle.ll,
        selectedIn.instInfo.exeOp === OpBundle.ld_w,
        selectedIn.instInfo.exeOp === OpBundle.ld_hu,
        selectedIn.instInfo.exeOp === OpBundle.ld_h,
        selectedIn.instInfo.exeOp === OpBundle.ld_bu,
        selectedIn.instInfo.exeOp === OpBundle.ld_b
      )
    )
    out.wb.instInfo.store.get.en := Mux(
      isAddrNotAligned,
      0.U,
      Cat(
        0.U(4.W),
        isAtomicStoreValid,
        selectedIn.instInfo.exeOp === OpBundle.st_w,
        selectedIn.instInfo.exeOp === OpBundle.st_h,
        selectedIn.instInfo.exeOp === OpBundle.st_b
      )
    )
    out.wb.instInfo.load.get.vaddr  := loadStoreAddr
    out.wb.instInfo.store.get.vaddr := loadStoreAddr
    out.wb.instInfo.store.get.data := MuxLookup(selectedIn.instInfo.exeOp.subOp, selectedIn.rightOperand)(
      immutable.Seq(
        OpBundle.st_b.subOp -> Mux(
          isSimpleMemory,
          Mux(
            maskEncode(1),
            Mux(
              maskEncode(0),
              Cat(selectedIn.rightOperand(7, 0), 0.U(24.W)),
              Cat(selectedIn.rightOperand(7, 0), 0.U(16.W))
            ),
            Mux(maskEncode(0), Cat(selectedIn.rightOperand(7, 0), 0.U(8.W)), selectedIn.rightOperand(7, 0))
          ),
          selectedIn.rightOperand
        ),
        OpBundle.st_h.subOp -> Mux(
          isSimpleMemory,
          Mux(
            maskEncode(1),
            Cat(selectedIn.rightOperand(15, 0), 0.U(16.W)),
            selectedIn.rightOperand(15, 0)
          ),
          selectedIn.rightOperand
        )
      )
    )
  }

  // alu

  // ALU input
  alu.io.isFlush                := io.isFlush
  alu.io.inputValid             := selectedIn.instInfo.isValid
  alu.io.aluInst.op             := selectedIn.instInfo.exeOp
  alu.io.aluInst.leftOperand    := selectedIn.leftOperand
  alu.io.aluInst.rightOperand   := selectedIn.rightOperand
  alu.io.aluInst.jumpBranchAddr := selectedIn.jumpBranchAddr // also load-store imm

  out.wb.gprWrite.data := DontCare

  when(io.isFlush) {
    isDbarBlockingReg := false.B
  }

  // cnt

  if (Param.isDiffTest) {
    out.wb.instInfo.timerInfo.get.isCnt := VecInit(OpBundle.rdcntvl_w.subOp, OpBundle.rdcntvh_w.subOp)
      .contains(selectedIn.instInfo.exeOp.subOp) && selectedIn.instInfo.exeOp.sel === OpBundle.sel_readTimeOrShift
    out.wb.instInfo.timerInfo.get.timer64 := peer.stableCounterReadPort.output
  }

  val cntOrShiftResult = Wire(UInt(32.W))
  if (Param.isUse3Unit) {
    cntOrShiftResult := DontCare
  } else {
    cntOrShiftResult := alu.io.result.shift
  }
  switch(selectedIn.instInfo.exeOp.subOp) {
    is(OpBundle.rdcntvl_w.subOp) {
      out.wb.instInfo.forbidParallelCommit := true.B
      cntOrShiftResult                     := io.peer.get.stableCounterReadPort.output(wordLength - 1, 0)
    }

    is(OpBundle.rdcntvh_w.subOp) {
      out.wb.instInfo.forbidParallelCommit := true.B
      cntOrShiftResult := io.peer.get.stableCounterReadPort
        .output(doubleWordLength - 1, wordLength)
    }
  }

  val fallThroughPc = selectedIn.branchInfo.fallThroughPc

  val csrResult = Wire(UInt(Width.Reg.data))
  csrResult := DontCare

  switch(selectedIn.instInfo.exeOp.sel) {
    is(OpBundle.sel_arthOrLogic) {
      if (Param.isUse3Unit) {
        out.wb.gprWrite.data := alu.io.result.logic
      }
    }
    is(OpBundle.sel_mulDiv) {
      if (Param.isUse3Unit) {
        out.wb.gprWrite.data := alu.io.result.mulDiv
      }
    }
    is(OpBundle.sel_readTimeOrShift) {
      out.wb.gprWrite.data := cntOrShiftResult
    }
    is(OpBundle.sel_simpleBranch, OpBundle.sel_misc) {
      out.wb.gprWrite.data := fallThroughPc
    }
    is(OpBundle.sel_csr) {
      out.wb.gprWrite.data := csrResult
    }
  }

  // excp

  val isSyscall = selectedIn.instInfo.exeOp === OpBundle.syscall
  val isBreak   = selectedIn.instInfo.exeOp === OpBundle.break_

  when(selectedIn.instInfo.exceptionPos === ExceptionPos.none) {
    when(isSyscall) {
      out.wb.instInfo.exceptionPos    := ExceptionPos.backend
      out.wb.instInfo.exceptionRecord := Csr.ExceptionIndex.sys
    }.elsewhen(isBreak) {
      out.wb.instInfo.exceptionPos    := ExceptionPos.backend
      out.wb.instInfo.exceptionRecord := Csr.ExceptionIndex.brk
    }
  }

  // csr

  val isCsrInst = selectedIn.instInfo.exeOp.sel === OpBundle.sel_csr

  def csrAddr = selectedIn.csrAddr

  io.peer.get.csrReadPort.en   := true.B
  io.peer.get.csrReadPort.addr := csrAddr

  val csrReadData = Mux(csrAddr(31), zeroWord, io.peer.get.csrReadPort.data)

  def csrWriteStorePort = io.peer.get.csrWriteStorePort
  csrWriteStorePort.valid     := false.B
  csrWriteStorePort.bits.en   := false.B
  csrWriteStorePort.bits.addr := csrAddr
  csrWriteStorePort.bits.data := DontCare

  csrResult := csrReadData
  when(isCsrInst) {
    switch(selectedIn.instInfo.exeOp.subOp) {
      is(OpBundle.csrrd.subOp) {}
      is(OpBundle.csrwr.subOp) {
        io.peer.get.csrWriteStorePort.valid   := outValid
        io.peer.get.csrWriteStorePort.bits.en := true.B
        csrWriteStorePort.bits.data           := selectedIn.leftOperand
      }
      is(OpBundle.csrxchg.subOp) {
        io.peer.get.csrWriteStorePort.valid   := outValid
        io.peer.get.csrWriteStorePort.bits.en := true.B
        // lop: write value  rop: mask
        val gprWriteDataVec = Wire(Vec(wordLength, Bool()))
        selectedIn.leftOperand.asBools
          .lazyZip(selectedIn.rightOperand.asBools)
          .lazyZip(csrReadData.asBools)
          .lazyZip(gprWriteDataVec)
          .foreach {
            case (write, mask, origin, target) =>
              target := Mux(mask, write, origin)
          }
        csrWriteStorePort.bits.data := gprWriteDataVec.asUInt
      }
    }
  }

  // branch

  val branchSetPort = io.peer.get.branchSetPort

  // branch set
  branchSetPort    := DontCare
  branchSetPort.en := false.B

  val feedbackFtq      = io.peer.get.feedbackFtq
  val aluCalcJumpEn    = alu.io.result.jumpEn
  val inFtqInfo        = selectedIn.instInfo.ftqInfo
  val inFtqPredictInfo = selectedIn.branchInfo.ftqPredictInfo

  val ftqQueryPc = selectedIn.branchInfo.predictJumpAddr

  // mis predict
  val branchDirectionMispredict = aluCalcJumpEn ^ inFtqPredictInfo.predictBranch
  // val jumpAddr = Mux(
  //   selectedIn.instInfo.exeOp === OpBundle.jirl,
  //   selectedIn.leftOperand ,
  //   DontCare
  // )
  val branchTargetMispredict = (
    aluCalcJumpEn &&
      inFtqPredictInfo.predictBranch &&
      Mux(
        selectedIn.instInfo.exeOp === OpBundle.jirl,
        selectedIn.leftOperand =/= selectedIn.branchInfo.predictSubImm,
        !selectedIn.branchInfo.immPredictCorrect
      )
  ) || (
    !aluCalcJumpEn &&
      !inFtqPredictInfo.predictBranch &&
      inFtqInfo.isLastInBlock &&
      !selectedIn.branchInfo.fallThroughPredictCorrect
  )

  // is branch
  val isBranchInst = selectedIn.branchInfo.isBranch

  val branchBlockingReg = RegInit(false.B)
  when(branchBlockingReg) {
    isComputed := false.B
  }

  val isRedirect = (branchDirectionMispredict || branchTargetMispredict) && isBranchInst
  when(isRedirect && outValid) {
    branchBlockingReg                     := true.B
    out.wb.instInfo.ftqInfo.isLastInBlock := true.B
  }

  if (Param.usePmu) {
    out.wb.instInfo.ftqCommitInfo.directionMispredict.get := branchDirectionMispredict && isBranchInst
    out.wb.instInfo.ftqCommitInfo.targetMispredict.get    := branchTargetMispredict && isBranchInst
  }

  val isBlocking = branchBlockingReg || isDbarBlockingReg

  branchSetPort.en    := isRedirect && !isBlocking && outValid
  branchSetPort.ftqId := selectedIn.instInfo.ftqInfo.ftqId

  val jumpAddr = Mux(
    selectedIn.instInfo.exeOp === OpBundle.jirl,
    selectedIn.leftOperand + selectedIn.jumpBranchAddr,
    selectedIn.jumpBranchAddr
  )

  branchSetPort.pcAddr := Mux(
    aluCalcJumpEn,
    jumpAddr,
    fallThroughPc
  )

  feedbackFtq.fixGhrBundle.isExeFixValid := RegNext(
    (isRedirect || (!selectedIn.branchInfo.ftqPredictInfo.isPredictValid && isBranchInst)) &&
      !isBlocking && outValid
  )
  feedbackFtq.fixGhrBundle.exeFixIsTaken := RegNext(aluCalcJumpEn)

  if (Param.exeFeedBackFtqDelay) {

    feedbackFtq.commitBundle.ftqMetaUpdateValid := (RegNext(isBranchInst, false.B) ||
      (RegNext(!isBranchInst, false.B) && RegNext(inFtqPredictInfo.predictBranch, false.B))) && RegNext(
      !isBlocking,
      false.B
    ) && RegNext(outValid)
    feedbackFtq.commitBundle.ftqMetaUpdateFtbDirty := RegNext(branchTargetMispredict, false.B) ||
      (RegNext(aluCalcJumpEn, false.B) && !RegNext(inFtqInfo.isLastInBlock, false.B)) ||
      (RegNext(!isBranchInst, false.B) && RegNext(inFtqPredictInfo.predictBranch, false.B))
    feedbackFtq.commitBundle.ftqUpdateMetaId          := RegNext(inFtqInfo.ftqId, 0.U)
    feedbackFtq.commitBundle.ftqMetaUpdateJumpTarget  := RegNext(jumpAddr, 0.U)
    feedbackFtq.commitBundle.ftqMetaUpdateFallThrough := RegNext(fallThroughPc, 0.U)
    feedbackFtq.commitBundle.fetchLastIdx             := RegNext(inFtqPredictInfo.idxInBlock, 0.U)
  } else {
    feedbackFtq.commitBundle.ftqMetaUpdateValid := (isBranchInst || (!isBranchInst && inFtqPredictInfo.predictBranch)) && !branchBlockingReg
    feedbackFtq.commitBundle.ftqMetaUpdateFtbDirty := branchTargetMispredict ||
      (aluCalcJumpEn && !inFtqInfo.isLastInBlock) || (!isBranchInst && inFtqPredictInfo.predictBranch)
    feedbackFtq.commitBundle.ftqUpdateMetaId          := inFtqInfo.ftqId
    feedbackFtq.commitBundle.ftqMetaUpdateJumpTarget  := jumpAddr
    feedbackFtq.commitBundle.ftqMetaUpdateFallThrough := fallThroughPc
    feedbackFtq.commitBundle.fetchLastIdx             := inFtqPredictInfo.idxInBlock
  }

  // out.wb.instInfo.ftqCommitInfo.isBranchSuccess := aluCalcJumpEn
  out.wb.instInfo.ftqCommitInfo.isRedirect := isRedirect || selectedIn.instInfo.ftqCommitInfo.isRedirect

  out.commitFtqPort.isTrainValid := isBranchInst && out.wb.instInfo.ftqInfo.isLastInBlock && !branchBlockingReg && outValid
  out.commitFtqPort.ftqId                          := selectedIn.instInfo.ftqInfo.ftqId
  out.commitFtqPort.branchTakenMeta.isTaken        := aluCalcJumpEn
  out.commitFtqPort.branchTakenMeta.branchType     := selectedIn.branchInfo.branchType
  out.commitFtqPort.branchTakenMeta.predictedTaken := inFtqPredictInfo.predictBranch

  val isErtn = selectedIn.instInfo.exeOp === OpBundle.ertn
  val isIdle = selectedIn.instInfo.exeOp === OpBundle.idle

  when(isBranchInst || isIdle || isErtn) {
    out.wb.instInfo.forbidParallelCommit := true.B
  }

  when(io.isFlush) {
    outValid          := false.B
    branchBlockingReg := false.B
  }
}
