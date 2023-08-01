package pipeline.memory

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import control.csrBundles.{EraBundle, LlbctlBundle}
import control.enums.ExceptionPos
import memory.bundles.CacheMaintenanceControlNdPort
import pipeline.common.BaseStage
import pipeline.execution.ExeNdPort
import pipeline.memory.enums.CacheMaintenanceTargetType
import spec.Param.isDiffTest
import spec._

import scala.collection.immutable

class ExeForMemPeerPort extends Bundle {
  val csr = Input(new Bundle {
    val llbctl = new LlbctlBundle
    val era    = new EraBundle
  })
  val dbarFinish = Input(Bool())
}

class ExeForMemStage
    extends BaseStage(
      new ExeNdPort,
      new AddrTransNdPort,
      ExeNdPort.default,
      Some(new ExeForMemPeerPort)
    ) {
  val out = resultOutReg.bits

  // Fallback
  out                             := DontCare
  out.instInfo                    := selectedIn.instInfo
  out.cacheMaintenance.control    := CacheMaintenanceControlNdPort.default
  out.isAtomicStore               := false.B
  out.tlbMaintenance.isRead       := false.B
  out.tlbMaintenance.isSearch     := false.B
  out.tlbMaintenance.isFill       := false.B
  out.tlbMaintenance.isWrite      := false.B
  out.tlbMaintenance.isInvalidate := false.B
  out.gprAddr                     := selectedIn.gprWritePort.addr
  resultOutReg.valid              := isComputed && selectedIn.instInfo.isValid

  val isDbarBlockingReg = RegInit(false.B)
  // dbar start
  when(selectedIn.instInfo.isValid && selectedIn.exeOp === ExeInst.Op.dbar) {
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
  val isAtomicStoreValid = io.peer.get.csr.llbctl.rollb && selectedIn.exeOp === ExeInst.Op.sc
  val isRead =
    VecInit(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.ld_w, ExeInst.Op.ll)
      .contains(selectedIn.exeOp)
  val isWrite =
    VecInit(ExeInst.Op.st_b, ExeInst.Op.st_h, ExeInst.Op.st_w).contains(selectedIn.exeOp) || isAtomicStoreValid
  val isValidLoadStore = (isRead || isWrite) && !isAddrNotAligned
  out.memRequest.write.data := selectedIn.rightOperand
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.st_b) {
      out.memRequest.mask := Mux(
        maskEncode(1),
        Mux(maskEncode(0), "b1000".U, "b0100".U),
        Mux(maskEncode(0), "b0010".U, "b0001".U)
      )
    }
    is(ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.st_h) {
      when(maskEncode(0)) {
        isAddrNotAligned := true.B
      }
      out.memRequest.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
    }
    is(ExeInst.Op.ld_w, ExeInst.Op.ll, ExeInst.Op.st_w, ExeInst.Op.sc) {
      isAddrNotAligned    := maskEncode.orR
      out.memRequest.mask := "b1111".U
    }
  }
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.st_b) {
      out.memRequest.write.data := Cat(
        Seq.fill(wordLength / byteLength)(selectedIn.rightOperand(byteLength - 1, 0))
      )
    }
    is(ExeInst.Op.st_h) {
      out.memRequest.write.data := Cat(Seq.fill(2)(selectedIn.rightOperand(wordLength / 2 - 1, 0)))
    }
  }
  out.memRequest.isValid            := isValidLoadStore
  out.memRequest.addr               := loadStoreAddr
  out.memRequest.read.isUnsigned    := VecInit(ExeInst.Op.ld_bu, ExeInst.Op.ld_hu).contains(selectedIn.exeOp)
  out.memRequest.rw                 := Mux(isWrite, ReadWriteSel.write, ReadWriteSel.read)
  out.isAtomicStore                 := selectedIn.exeOp === ExeInst.Op.sc
  out.instInfo.forbidParallelCommit := isValidLoadStore
  out.instInfo.isStore              := isWrite && !isAddrNotAligned

  // Handle exception
  when(selectedIn.instInfo.exceptionPos === ExceptionPos.none && isAddrNotAligned) {
    out.instInfo.exceptionPos    := ExceptionPos.backend
    out.instInfo.exceptionRecord := Csr.ExceptionIndex.ale
  }

  // Handle TLB maintenance
  out.tlbMaintenance.registerAsid   := selectedIn.leftOperand(9, 0)
  out.tlbMaintenance.virtAddr       := selectedIn.rightOperand
  out.tlbMaintenance.invalidateInst := selectedIn.tlbInvalidateInst
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.tlbfill) {
      out.tlbMaintenance.isFill := true.B
    }
    is(ExeInst.Op.tlbrd) {
      out.tlbMaintenance.isRead := true.B
    }
    is(ExeInst.Op.tlbsrch) {
      out.tlbMaintenance.isSearch := true.B
    }
    is(ExeInst.Op.tlbwr) {
      out.tlbMaintenance.isWrite := true.B
    }
    is(ExeInst.Op.invtlb) {
      out.tlbMaintenance.isInvalidate := true.B
    }
  }

  // Cache maintenance
  val cacopAddr = WireDefault(selectedIn.leftOperand + selectedIn.rightOperand)
  val isCacop   = WireDefault(selectedIn.exeOp === ExeInst.Op.cacop)
  when(isCacop) {
    out.memRequest.addr               := cacopAddr
    out.instInfo.forbidParallelCommit := true.B

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

  // Difftest
  if (isDiffTest) {
    out.instInfo.load.get.en := Mux(
      isAddrNotAligned,
      0.U,
      Cat(
        0.U(2.W),
        selectedIn.exeOp === ExeInst.Op.ll,
        selectedIn.exeOp === ExeInst.Op.ld_w,
        selectedIn.exeOp === ExeInst.Op.ld_hu,
        selectedIn.exeOp === ExeInst.Op.ld_h,
        selectedIn.exeOp === ExeInst.Op.ld_bu,
        selectedIn.exeOp === ExeInst.Op.ld_b
      )
    )
    out.instInfo.store.get.en := Mux(
      isAddrNotAligned,
      0.U,
      Cat(
        0.U(4.W),
        isAtomicStoreValid,
        selectedIn.exeOp === ExeInst.Op.st_w,
        selectedIn.exeOp === ExeInst.Op.st_h,
        selectedIn.exeOp === ExeInst.Op.st_b
      )
    )
    out.instInfo.load.get.vaddr  := loadStoreAddr
    out.instInfo.store.get.vaddr := loadStoreAddr
    out.instInfo.store.get.data := MuxLookup(selectedIn.exeOp, selectedIn.rightOperand)(
      immutable.Seq(
        ExeInst.Op.st_b -> Mux(
          maskEncode(1),
          Mux(
            maskEncode(0),
            Cat(selectedIn.rightOperand(7, 0), 0.U(24.W)),
            Cat(selectedIn.rightOperand(7, 0), 0.U(16.W))
          ),
          Mux(maskEncode(0), Cat(selectedIn.rightOperand(7, 0), 0.U(8.W)), selectedIn.rightOperand(7, 0))
        ),
        ExeInst.Op.st_h -> Mux(
          maskEncode(1),
          Cat(selectedIn.rightOperand(15, 0), 0.U(16.W)),
          selectedIn.rightOperand(15, 0)
        )
      )
    )
  }

  when(io.isFlush) {
    isDbarBlockingReg := false.B
  }
}
