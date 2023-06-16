package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import spec.ExeInst.Sel
import spec._
import control.bundles.PipelineControlNdPort
import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals._
import spec.Param.{ExeStageState => State}
import pipeline.execution.Alu
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.execution.bundles.JumpBranchInfoNdPort
import common.bundles.PcSetPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import common.enums.ReadWriteSel
import control.csrRegsBundles.LlbctlBundle
import pipeline.mem.bundles.MemRequestNdPort
import pipeline.execution.bundles.ExeResultPort
import pipeline.common.BaseStage
import pipeline.mem.AddrTransNdPort

import scala.collection.immutable
import control.csrRegsBundles.EraBundle

// throw exception: 地址未对齐 ale
class ExeForMemStage
    extends BaseStage(
      new ExeNdPort,
      new AddrTransNdPort,
      ExeNdPort.default,
      Some(new ExePeerPort)
    ) {

  isComputed                 := true.B
  resultOutReg.valid         := isComputed && selectedIn.instInfo.isValid
  resultOutReg.bits.instInfo := selectedIn.instInfo

  // write-back information fallback
  // resultOutReg.bits.gprWrite.en   := false.B
  // resultOutReg.bits.gprWrite.addr := zeroWord
  // resultOutReg.bits.gprWrite.data := zeroWord
  resultOutReg.bits.gprAddr := selectedIn.gprWritePort.addr

  // // write-back information selection
  // resultOutReg.bits.gprWrite.en   := selectedIn.gprWritePort.en
  // resultOutReg.bits.gprWrite.addr := selectedIn.gprWritePort.addr

  // 指令未对齐
  val isAle = WireDefault(false.B)

  def csrWriteData = resultOutReg.bits.instInfo.csrWritePort.data

  switch(selectedIn.exeOp) {
    is(ExeInst.Op.invtlb) {
      // lop : asid  rop : virtual addr
      resultOutReg.bits.instInfo.tlbInfo.registerAsid := selectedIn.leftOperand(9, 0)
      resultOutReg.bits.instInfo.tlbInfo.virtAddr     := selectedIn.rightOperand
    }
  }

  when(selectedIn.instInfo.pc(1, 0).orR) {
    resultOutReg.bits.instInfo.isExceptionValid                          := true.B
    resultOutReg.bits.instInfo.exceptionRecords(Csr.ExceptionIndex.adef) := true.B
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

  resultOutReg.bits.memRequest.isValid         := (memReadEn || memWriteEn) && !isAle
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

  resultOutReg.bits.instInfo.load.en := Mux(
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
  resultOutReg.bits.instInfo.store.en := Mux(
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
  resultOutReg.bits.instInfo.load.vaddr  := loadStoreAddr
  resultOutReg.bits.instInfo.store.vaddr := loadStoreAddr
  resultOutReg.bits.instInfo.store.data := MuxLookup(selectedIn.exeOp, selectedIn.rightOperand)(
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

  io.peer.get.csrScoreboardChangePort.en   := selectedIn.instInfo.needCsr
  io.peer.get.csrScoreboardChangePort.addr := selectedIn.instInfo.csrWritePort.addr

}
