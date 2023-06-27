package pipeline.execution

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import control.csrBundles.{EraBundle, LlbctlBundle}
import control.enums.ExceptionPos
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.memory.AddrTransNdPort
import spec.Param.isDiffTest
import spec._

import scala.collection.immutable
import memory.bundles.TlbMaintenanceNdPort
import pipeline.memory.bundles.CacheMaintenanceInstNdPort
import pipeline.memory.enums.CacheMaintenanceTargetType

class ExeForMemPeerPort extends Bundle {
  val csrScoreboardChangePort = Output(new ScoreboardChangeNdPort)
  val csr = Input(new Bundle {
    val llbctl = new LlbctlBundle
    val era    = new EraBundle
  })
}

// throw exception: 地址未对齐 ale
class ExeForMemStage
    extends BaseStage(
      new ExeNdPort,
      new AddrTransNdPort,
      ExeNdPort.default,
      Some(new ExeForMemPeerPort)
    ) {

  isComputed        := true.B
  resultOutReg.bits := AddrTransNdPort.default

  resultOutReg.valid         := isComputed && selectedIn.instInfo.isValid
  resultOutReg.bits.instInfo := selectedIn.instInfo

  // write-back information fallback
  resultOutReg.bits.gprAddr := selectedIn.gprWritePort.addr

  // 指令未对齐
  val isAle = WireDefault(false.B)
  resultOutReg.bits.instInfo.exceptionPos := selectedIn.instInfo.exceptionPos
  when(selectedIn.instInfo.exceptionPos === ExceptionPos.none) {
    when(isAle) {
      resultOutReg.bits.instInfo.exceptionPos    := ExceptionPos.backend
      resultOutReg.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.ale
    }
  }

  def csrWriteData = resultOutReg.bits.instInfo.csrWritePort.data

  resultOutReg.bits.tlbMaintenance := TlbMaintenanceNdPort.default
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.tlbfill) {
      resultOutReg.bits.tlbMaintenance.isFill := true.B
    }
    is(ExeInst.Op.tlbrd) {
      resultOutReg.bits.tlbMaintenance.isRead := true.B
    }
    is(ExeInst.Op.tlbsrch) {
      resultOutReg.bits.tlbMaintenance.isSearch := true.B
    }
    is(ExeInst.Op.tlbwr) {
      resultOutReg.bits.tlbMaintenance.isWrite := true.B
    }
    is(ExeInst.Op.invtlb) {
      resultOutReg.bits.tlbMaintenance.isInvalidate := true.B
      // lop : asid  rop : virtual addr
      resultOutReg.bits.tlbMaintenance.registerAsid   := selectedIn.leftOperand(9, 0)
      resultOutReg.bits.tlbMaintenance.virtAddr       := selectedIn.rightOperand
      resultOutReg.bits.tlbMaintenance.invalidateInst := selectedIn.tlbInvalidateInst
    }
  }

  /** MemAccess
    */

  val loadStoreAddr = WireDefault(selectedIn.leftOperand + selectedIn.loadStoreImm)

  val memReadEn = WireDefault(
    VecInit(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.ld_w, ExeInst.Op.ll)
      .contains(selectedIn.exeOp)
  )
  val memWriteEn = WireDefault(
    VecInit(ExeInst.Op.st_b, ExeInst.Op.st_h, ExeInst.Op.st_w, ExeInst.Op.sc)
      .contains(selectedIn.exeOp)
  )
  val memLoadUnsigned = WireDefault(VecInit(ExeInst.Op.ld_bu, ExeInst.Op.ld_hu).contains(selectedIn.exeOp))

  val isLoadStore = (memReadEn || memWriteEn) && !isAle
  resultOutReg.bits.memRequest.isValid         := isLoadStore
  resultOutReg.bits.memRequest.addr            := Cat(loadStoreAddr(wordLength - 1, 2), 0.U(2.W))
  resultOutReg.bits.memRequest.write.data      := selectedIn.rightOperand
  resultOutReg.bits.memRequest.read.isUnsigned := memLoadUnsigned
  resultOutReg.bits.memRequest.rw              := Mux(memWriteEn, ReadWriteSel.write, ReadWriteSel.read)
  // mask
  val maskEncode = loadStoreAddr(1, 0)
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.st_b) {
      resultOutReg.bits.memRequest.mask := Mux(
        maskEncode(1),
        Mux(maskEncode(0), "b1000".U, "b0100".U),
        Mux(maskEncode(0), "b0010".U, "b0001".U)
      )
    }
    is(ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.st_h) {
      when(maskEncode(0)) {
        isAle := true.B // 未对齐
      }
      resultOutReg.bits.memRequest.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
    }
    is(ExeInst.Op.ld_w, ExeInst.Op.ll, ExeInst.Op.st_w, ExeInst.Op.sc) {
      isAle                             := maskEncode.orR
      resultOutReg.bits.memRequest.mask := "b1111".U
    }
  }
  switch(selectedIn.exeOp) {
    is(ExeInst.Op.st_b) {
      resultOutReg.bits.memRequest.write.data := Cat(
        Seq.fill(wordLength / byteLength)(selectedIn.rightOperand(byteLength - 1, 0))
      )
    }
    is(ExeInst.Op.st_h) {
      resultOutReg.bits.memRequest.write.data := Cat(Seq.fill(2)(selectedIn.rightOperand(wordLength / 2 - 1, 0)))
    }
  }

  if (isDiffTest) {
    resultOutReg.bits.instInfo.load.get.en := Mux(
      isAle,
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
    resultOutReg.bits.instInfo.store.get.en := Mux(
      isAle,
      0.U,
      Cat(
        0.U(4.W),
        io.peer.get.csr.llbctl.wcllb &&
          selectedIn.exeOp === ExeInst.Op.sc,
        selectedIn.exeOp === ExeInst.Op.st_w,
        selectedIn.exeOp === ExeInst.Op.st_h,
        selectedIn.exeOp === ExeInst.Op.st_b
      )
    )
    resultOutReg.bits.instInfo.load.get.vaddr  := loadStoreAddr
    resultOutReg.bits.instInfo.store.get.vaddr := loadStoreAddr
    resultOutReg.bits.instInfo.store.get.data := MuxLookup(selectedIn.exeOp, selectedIn.rightOperand)(
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

  when(isLoadStore) {
    resultOutReg.bits.instInfo.forbidParallelCommit := true.B
  }

  val cacopAddr = WireDefault(selectedIn.leftOperand + selectedIn.rightOperand)
  val isCacop   = WireDefault(selectedIn.exeOp === ExeInst.Op.cacop)

  when(isCacop) {
    resultOutReg.bits.memRequest.addr := cacopAddr

    switch(selectedIn.code(2, 0)) {
      is(0.U) {
        resultOutReg.bits.cacheMaintenance.target            := CacheMaintenanceTargetType.inst
        resultOutReg.bits.cacheMaintenance.control.isL1Valid := true.B
      }
      is(1.U) {
        resultOutReg.bits.cacheMaintenance.target            := CacheMaintenanceTargetType.data
        resultOutReg.bits.cacheMaintenance.control.isL1Valid := true.B
        resultOutReg.bits.instInfo.forbidParallelCommit      := true.B
      }
      is(2.U) {
        resultOutReg.bits.cacheMaintenance.control.isL2Valid := true.B
        resultOutReg.bits.instInfo.forbidParallelCommit      := true.B
      }
    }

    switch(selectedIn.code(4, 3)) {
      is(0.U) {
        resultOutReg.bits.cacheMaintenance.control.isInit := true.B
      }
      is(1.U) {
        resultOutReg.bits.cacheMaintenance.control.isCoherentByIndex := true.B
      }
      is(2.U) {
        resultOutReg.bits.cacheMaintenance.control.isCoherentByHit := true.B
      }
    }
  }

  io.peer.get.csrScoreboardChangePort.en   := selectedIn.instInfo.needCsr
  io.peer.get.csrScoreboardChangePort.addr := selectedIn.instInfo.csrWritePort.addr
  resultOutReg.bits.instInfo.isStore       := resultOutReg.bits.instInfo.store.get.en.orR
  resultOutReg.bits.instInfo.vaddr         := Mux(isCacop, cacopAddr, loadStoreAddr)
}
