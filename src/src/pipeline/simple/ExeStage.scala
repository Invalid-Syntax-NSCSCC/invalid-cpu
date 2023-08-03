package pipeline.simple

import chisel3._
import chisel3.util._
import common.BaseStage
import common.bundles._
import control.bundles._
import control.csrBundles._
import execution.Alu
import frontend.bundles._
import pipeline.common.bundles.RobQueryPcPort
import pipeline.simple.bundles.InstInfoNdPort
import spec._

class ExeNdPort extends Bundle {
  // Micro-instruction for execution stage
  val exeSel = UInt(Param.Width.exeSel)
  val exeOp  = UInt(Param.Width.exeOp)
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
  val feedbackFtq = Flipped(new ExeFtqPort)

  val robQueryPcPort = Flipped(new RobQueryPcPort)
}

// class MainExeStage
//     extends BaseStage(
//       new ExeNdPort,
//       // new DummyNdPort,
//       ExeNdPort.default,
//       Some(new ExePeerPort)
//     ) {
//   val out  = io.out.bits
//   val peer = io.peer.get

//   // Fallback
//   // ALU module
//   val alu = Module(new Alu)

//   isComputed                      := alu.io.outputValid && !(out.isLoadStore && !io.out.ready)
//   out                             := DontCare
//   out.cacheMaintenance.control    := CacheMaintenanceControlNdPort.default
//   out.isAtomicStore               := false.B
//   out.tlbMaintenance.isRead       := false.B
//   out.tlbMaintenance.isSearch     := false.B
//   out.tlbMaintenance.isFill       := false.B
//   out.tlbMaintenance.isWrite      := false.B
//   out.tlbMaintenance.isInvalidate := false.B
//   out.wb.instInfo                 := selectedIn.instInfo
//   out.wb.gprWrite.en              := selectedIn.gprWritePort.en
//   out.wb.gprWrite.addr            := selectedIn.gprWritePort.addr
//   io.out.valid                    := isComputed && selectedIn.instInfo.isValid

//   // memory

//   val isDbarBlockingReg = RegInit(false.B)
//   // dbar start
//   when(selectedIn.instInfo.isValid && selectedIn.exeOp === ExeInst.Op.dbar) {
//     isDbarBlockingReg := true.B
//   }
//   // dbar execute and finish
//   when(isDbarBlockingReg) {
//     isComputed := false.B
//     when(io.peer.get.dbarFinish) {
//       isDbarBlockingReg := false.B
//     }
//   }

//   // Generate address
//   val isAddrNotAligned   = WireDefault(false.B)
//   val loadStoreAddr      = selectedIn.leftOperand + selectedIn.loadStoreImm
//   val maskEncode         = loadStoreAddr(1, 0)
//   val isAtomicStoreValid = io.peer.get.csr.llbctl.rollb && selectedIn.exeOp === ExeInst.Op.sc
//   val isRead =
//     VecInit(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.ld_w, ExeInst.Op.ll)
//       .contains(selectedIn.exeOp)
//   val isWrite =
//     VecInit(ExeInst.Op.st_b, ExeInst.Op.st_h, ExeInst.Op.st_w).contains(selectedIn.exeOp) || isAtomicStoreValid
//   val isValidLoadStore = (isRead || isWrite) && !isAddrNotAligned
//   out.memRequest.write.data := selectedIn.rightOperand
//   switch(selectedIn.exeOp) {
//     is(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.st_b) {
//       out.memRequest.mask := Mux(
//         maskEncode(1),
//         Mux(maskEncode(0), "b1000".U, "b0100".U),
//         Mux(maskEncode(0), "b0010".U, "b0001".U)
//       )
//     }
//     is(ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.st_h) {
//       when(maskEncode(0)) {
//         isAddrNotAligned := true.B
//       }
//       out.memRequest.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
//     }
//     is(ExeInst.Op.ld_w, ExeInst.Op.ll, ExeInst.Op.st_w, ExeInst.Op.sc) {
//       isAddrNotAligned    := maskEncode.orR
//       out.memRequest.mask := "b1111".U
//     }
//   }
//   switch(selectedIn.exeOp) {
//     is(ExeInst.Op.st_b) {
//       out.memRequest.write.data := Cat(
//         Seq.fill(wordLength / byteLength)(selectedIn.rightOperand(byteLength - 1, 0))
//       )
//     }
//     is(ExeInst.Op.st_h) {
//       out.memRequest.write.data := Cat(Seq.fill(2)(selectedIn.rightOperand(wordLength / 2 - 1, 0)))
//     }
//   }
//   out.memRequest.isValid               := isValidLoadStore
//   out.memRequest.addr                  := loadStoreAddr
//   out.memRequest.read.isUnsigned       := VecInit(ExeInst.Op.ld_bu, ExeInst.Op.ld_hu).contains(selectedIn.exeOp)
//   out.memRequest.rw                    := Mux(isWrite, ReadWriteSel.write, ReadWriteSel.read)
//   out.isAtomicStore                    := selectedIn.exeOp === ExeInst.Op.sc
//   out.wb.instInfo.forbidParallelCommit := isValidLoadStore
//   out.wb.instInfo.isStore              := isWrite && !isAddrNotAligned

//   // Handle exception
//   when(selectedIn.instInfo.exceptionPos === ExceptionPos.none && isAddrNotAligned) {
//     out.wb.instInfo.exceptionPos    := ExceptionPos.backend
//     out.wb.instInfo.exceptionRecord := Csr.ExceptionIndex.ale
//   }

//   // Handle TLB maintenance
//   out.tlbMaintenance.registerAsid   := selectedIn.leftOperand(9, 0)
//   out.tlbMaintenance.virtAddr       := selectedIn.rightOperand
//   out.tlbMaintenance.invalidateInst := selectedIn.tlbInvalidateInst
//   switch(selectedIn.exeOp) {
//     is(ExeInst.Op.tlbfill) {
//       out.tlbMaintenance.isFill := true.B
//     }
//     is(ExeInst.Op.tlbrd) {
//       out.tlbMaintenance.isRead := true.B
//     }
//     is(ExeInst.Op.tlbsrch) {
//       out.tlbMaintenance.isSearch := true.B
//     }
//     is(ExeInst.Op.tlbwr) {
//       out.tlbMaintenance.isWrite := true.B
//     }
//     is(ExeInst.Op.invtlb) {
//       out.tlbMaintenance.isInvalidate := true.B
//     }
//   }

//   // Cache maintenance
//   val cacopAddr = WireDefault(selectedIn.leftOperand + selectedIn.rightOperand)
//   val isCacop   = WireDefault(selectedIn.exeOp === ExeInst.Op.cacop)
//   when(isCacop) {
//     out.memRequest.addr                  := cacopAddr
//     out.wb.instInfo.forbidParallelCommit := true.B

//     switch(selectedIn.code(2, 0)) {
//       is(0.U) {
//         out.cacheMaintenance.target            := CacheMaintenanceTargetType.inst
//         out.cacheMaintenance.control.isL1Valid := true.B
//       }
//       is(1.U) {
//         out.cacheMaintenance.target            := CacheMaintenanceTargetType.data
//         out.cacheMaintenance.control.isL1Valid := true.B
//       }
//       is(2.U) {
//         out.cacheMaintenance.control.isL2Valid := true.B
//       }
//     }

//     switch(selectedIn.code(4, 3)) {
//       is(0.U) {
//         out.cacheMaintenance.control.isInit := true.B
//       }
//       is(1.U) {
//         out.cacheMaintenance.control.isCoherentByIndex := true.B
//       }
//       is(2.U) {
//         out.cacheMaintenance.control.isCoherentByHit := true.B
//       }
//     }
//   }

//   out.isLoadStore := selectedIn.exeSel === ExeInst.Sel.loadStore && out.wb.instInfo.exceptionPos === ExceptionPos.none

//   // Difftest
//   if (isDiffTest) {
//     out.wb.instInfo.load.get.en := Mux(
//       isAddrNotAligned,
//       0.U,
//       Cat(
//         0.U(2.W),
//         selectedIn.exeOp === ExeInst.Op.ll,
//         selectedIn.exeOp === ExeInst.Op.ld_w,
//         selectedIn.exeOp === ExeInst.Op.ld_hu,
//         selectedIn.exeOp === ExeInst.Op.ld_h,
//         selectedIn.exeOp === ExeInst.Op.ld_bu,
//         selectedIn.exeOp === ExeInst.Op.ld_b
//       )
//     )
//     out.wb.instInfo.store.get.en := Mux(
//       isAddrNotAligned,
//       0.U,
//       Cat(
//         0.U(4.W),
//         isAtomicStoreValid,
//         selectedIn.exeOp === ExeInst.Op.st_w,
//         selectedIn.exeOp === ExeInst.Op.st_h,
//         selectedIn.exeOp === ExeInst.Op.st_b
//       )
//     )
//     out.wb.instInfo.load.get.vaddr  := loadStoreAddr
//     out.wb.instInfo.store.get.vaddr := loadStoreAddr
//     out.wb.instInfo.store.get.data := MuxLookup(selectedIn.exeOp, selectedIn.rightOperand)(
//       immutable.Seq(
//         ExeInst.Op.st_b -> Mux(
//           maskEncode(1),
//           Mux(
//             maskEncode(0),
//             Cat(selectedIn.rightOperand(7, 0), 0.U(24.W)),
//             Cat(selectedIn.rightOperand(7, 0), 0.U(16.W))
//           ),
//           Mux(maskEncode(0), Cat(selectedIn.rightOperand(7, 0), 0.U(8.W)), selectedIn.rightOperand(7, 0))
//         ),
//         ExeInst.Op.st_h -> Mux(
//           maskEncode(1),
//           Cat(selectedIn.rightOperand(15, 0), 0.U(16.W)),
//           selectedIn.rightOperand(15, 0)
//         )
//       )
//     )
//   }

//   // alu

//   // ALU input
//   alu.io.isFlush                := io.isFlush
//   alu.io.inputValid             := selectedIn.instInfo.isValid
//   alu.io.aluInst.op             := selectedIn.exeOp
//   alu.io.aluInst.leftOperand    := selectedIn.leftOperand
//   alu.io.aluInst.rightOperand   := selectedIn.rightOperand
//   alu.io.aluInst.jumpBranchAddr := selectedIn.jumpBranchAddr // also load-store imm

//   out.wb.gprWrite.data      := DontCare
//   peer.robQueryPcPort.robId := selectedIn.instInfo.robId

//   when(io.isFlush) {
//     isDbarBlockingReg := false.B
//   }

//   val fallThroughPc = peer.robQueryPcPort.pc + 4.U

//   switch(selectedIn.exeSel) {
//     is(Sel.logic) {
//       out.wb.gprWrite.data := alu.io.result.logic
//     }
//     is(Sel.shift) {
//       out.wb.gprWrite.data := alu.io.result.shift
//     }
//     is(Sel.arithmetic) {
//       out.wb.gprWrite.data := alu.io.result.arithmetic
//     }
//     is(Sel.jumpBranch) {
//       out.wb.gprWrite.data := fallThroughPc
//     }
//   }

//   // excp

//   val isSyscall = selectedIn.exeOp === ExeInst.Op.syscall
//   val isBreak   = selectedIn.exeOp === ExeInst.Op.break_

//   when(selectedIn.instInfo.exceptionPos === ExceptionPos.none) {
//     when(isSyscall) {
//       out.wb.instInfo.exceptionPos    := ExceptionPos.backend
//       out.wb.instInfo.exceptionRecord := Csr.ExceptionIndex.sys
//     }.elsewhen(isBreak) {
//       out.wb.instInfo.exceptionPos    := ExceptionPos.backend
//       out.wb.instInfo.exceptionRecord := Csr.ExceptionIndex.brk
//     }
//   }

//   // cnt

//   if (isDiffTest) {
//     out.wb.instInfo.timerInfo.get.isCnt := VecInit(ExeInst.Op.rdcntvl_w, ExeInst.Op.rdcntvh_w)
//       .contains(selectedIn.exeOp)
//     out.wb.instInfo.timerInfo.get.timer64 := peer.stableCounterReadPort.output
//   }

//   switch(selectedIn.exeOp) {
//     is(ExeInst.Op.rdcntvl_w) {
//       out.wb.gprWrite.data := io.peer.get.stableCounterReadPort.output(wordLength - 1, 0)
//     }

//     is(ExeInst.Op.rdcntvh_w) {
//       out.wb.gprWrite.data := io.peer.get.stableCounterReadPort
//         .output(doubleWordLength - 1, wordLength)
//     }
//   }

//   // csr

//   def csrAddr = selectedIn.csrAddr

//   io.peer.get.csrReadPort.en   := true.B
//   io.peer.get.csrReadPort.addr := csrAddr

//   val csrReadData = Mux(csrAddr(31), zeroWord, io.peer.get.csrReadPort.data)

//   def csrWriteStorePort = io.peer.get.csrWriteStorePort
//   csrWriteStorePort.valid     := false.B
//   csrWriteStorePort.bits.en   := false.B
//   csrWriteStorePort.bits.addr := csrAddr
//   csrWriteStorePort.bits.data := DontCare

//   switch(selectedIn.exeOp) {
//     is(ExeInst.Op.csrrd) {
//       out.wb.gprWrite.data := csrReadData
//     }
//     is(ExeInst.Op.csrwr) {
//       io.peer.get.csrWriteStorePort.valid   := true.B
//       io.peer.get.csrWriteStorePort.bits.en := true.B
//       csrWriteStorePort.bits.data           := selectedIn.leftOperand
//       out.wb.gprWrite.data                  := csrReadData
//     }
//     is(ExeInst.Op.csrxchg) {
//       io.peer.get.csrWriteStorePort.valid   := true.B
//       io.peer.get.csrWriteStorePort.bits.en := true.B
//       // lop: write value  rop: mask
//       val gprWriteDataVec = Wire(Vec(wordLength, Bool()))
//       selectedIn.leftOperand.asBools
//         .lazyZip(selectedIn.rightOperand.asBools)
//         .lazyZip(csrReadData.asBools)
//         .lazyZip(gprWriteDataVec)
//         .foreach {
//           case (write, mask, origin, target) =>
//             target := Mux(mask, write, origin)
//         }
//       csrWriteStorePort.bits.data := gprWriteDataVec.asUInt
//       out.wb.gprWrite.data        := csrReadData
//     }
//   }

//   // branch

//   val branchEnableFlag = RegInit(true.B)

//   val branchSetPort = io.peer.get.branchSetPort

//   // branch set
//   branchSetPort := BackendRedirectPcNdPort.default

//   val feedbackFtq    = io.peer.get.feedbackFtq
//   val jumpBranchInfo = WireDefault(alu.io.result.jumpBranchInfo)
//   val inFtqInfo      = WireDefault(selectedIn.instInfo.ftqInfo)

//   feedbackFtq.queryPcBundle.ftqId := selectedIn.instInfo.ftqInfo.ftqId + 1.U
//   val ftqQueryPc = feedbackFtq.queryPcBundle.pc

//   // mis predict
//   val branchDirectionMispredict = jumpBranchInfo.en ^ inFtqInfo.predictBranch
//   val branchTargetMispredict = (
//     jumpBranchInfo.en &&
//       inFtqInfo.predictBranch &&
//       jumpBranchInfo.pcAddr =/= ftqQueryPc
//   ) || (
//     !jumpBranchInfo.en &&
//       !inFtqInfo.predictBranch &&
//       inFtqInfo.isLastInBlock &&
//       fallThroughPc =/= ftqQueryPc
//   )

//   // is branch
//   val isBranchInst = selectedIn.instInfo.ftqCommitInfo.isBranch

//   val isRedirect = (branchDirectionMispredict || branchTargetMispredict) && branchEnableFlag && isBranchInst
//   when(isRedirect) {
//     branchEnableFlag                      := false.B
//     out.wb.instInfo.ftqInfo.isLastInBlock := true.B
//   }

//   if (Param.usePmu) {
//     out.wb.instInfo.ftqCommitInfo.directionMispredict.get := branchDirectionMispredict && branchEnableFlag && isBranchInst
//     out.wb.instInfo.ftqCommitInfo.targetMispredict.get := branchTargetMispredict && branchEnableFlag && isBranchInst
//   }

//   branchSetPort.en    := isRedirect
//   branchSetPort.ftqId := selectedIn.instInfo.ftqInfo.ftqId

//   branchSetPort.pcAddr := Mux(
//     jumpBranchInfo.en,
//     jumpBranchInfo.pcAddr,
//     fallThroughPc
//   )

//   if (Param.exeFeedBackFtqDelay) {

//     feedbackFtq.commitBundle.ftqMetaUpdateValid := RegNext(isBranchInst, false.B) && RegNext(
//       branchEnableFlag,
//       false.B
//     )
//     feedbackFtq.commitBundle.ftqMetaUpdateFtbDirty := RegNext(branchTargetMispredict, false.B) ||
//       (RegNext(jumpBranchInfo.en, false.B) && !RegNext(inFtqInfo.isLastInBlock, false.B))
//     feedbackFtq.commitBundle.ftqUpdateMetaId          := RegNext(inFtqInfo.ftqId, 0.U)
//     feedbackFtq.commitBundle.ftqMetaUpdateJumpTarget  := RegNext(jumpBranchInfo.pcAddr, 0.U)
//     feedbackFtq.commitBundle.ftqMetaUpdateFallThrough := RegNext(fallThroughPc, 0.U)
//   } else {

//     feedbackFtq.commitBundle.ftqMetaUpdateValid := isBranchInst && branchEnableFlag
//     feedbackFtq.commitBundle.ftqMetaUpdateFtbDirty := branchTargetMispredict ||
//       (jumpBranchInfo.en && !inFtqInfo.isLastInBlock)
//     feedbackFtq.commitBundle.ftqUpdateMetaId          := inFtqInfo.ftqId
//     feedbackFtq.commitBundle.ftqMetaUpdateJumpTarget  := jumpBranchInfo.pcAddr
//     feedbackFtq.commitBundle.ftqMetaUpdateFallThrough := fallThroughPc
//   }

//   out.wb.instInfo.ftqCommitInfo.isBranchSuccess := jumpBranchInfo.en
//   out.wb.instInfo.ftqCommitInfo.isRedirect      := isRedirect || selectedIn.instInfo.ftqCommitInfo.isRedirect

//   val isErtn = WireDefault(selectedIn.exeOp === ExeInst.Op.ertn)
//   val isIdle = WireDefault(selectedIn.exeOp === ExeInst.Op.idle)

//   when(isBranchInst || isIdle || isErtn) {
//     out.wb.instInfo.forbidParallelCommit := true.B
//   }

//   when(io.isFlush) {
//     branchEnableFlag := true.B
//   }
// }
