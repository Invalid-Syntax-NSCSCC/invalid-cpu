package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import pipeline.ctrl.bundles.PipelineControlNDPort
import spec.PipelineStageIndex
import pipeline.writeback.bundles.InstInfoNdPort
import common.bundles.RfWriteNdPort
import common.bundles.PassThroughPort
import pipeline.ctrl.bundles.CsrWriteNdPort
import pipeline.ctrl.bundles.CuToCsrNdPort
import spec.CsrRegs
import pipeline.ctrl.bundles.CsrToCuNdPort
import spec.Width
import spec.zeroWord

// TODO: Add stall to frontend ?
// TODO: Add flush to stages
// TODO: Add deal exceptions
// 优先解决多发射中index小的流水线
class Cu(
  ctrlControlNum: Int = Param.ctrlControlNum,
  writeNum:       Int = Param.csrRegsWriteNum,
  dispatchNum:    Int = Param.dispatchInstNum)
    extends Module {
  val io = IO(new Bundle {

    /** 回写与异常处理
      */
    // `WbStage` -> `Cu`
    val gprWritePassThroughPorts = new PassThroughPort(Vec(dispatchNum, new RfWriteNdPort))
    val instInfoPorts            = Input(Vec(dispatchNum, new InstInfoNdPort))
    // `Cu` -> `Csr`, 软件写
    val csrWritePorts = Output(Vec(writeNum, new CsrWriteNdPort))
    // `Cu` -> `Csr`, 硬件写
    val csrMessage = Output(new CuToCsrNdPort)
    // `Csr` -> `Cu`
    val csrValues = Input(new CsrToCuNdPort)
    // `Csr` -> `Pc`  待接入
    val newPc = Output(UInt(Width.Reg.data))

    /** 暂停信号
      */
    // `ExeStage` -> `Cu`
    val exeStallRequest = Input(Bool())
    // `MemStage` -> `Cu`
    val memStallRequest = Input(Bool())
    // `Cu` -> `IssueStage`, `RegReadStage`, `ExeStage`, `MemStage`
    val pipelineControlPorts = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
  })

  /** Stall 暂停流水线前面部分
    */

  io.pipelineControlPorts.foreach(_ := PipelineControlNDPort.default)
  // `ExeStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage` (STALL ITSELF)
  Seq(PipelineStageIndex.issueStage, PipelineStageIndex.regReadStage, PipelineStageIndex.exeStage)
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.exeStallRequest)
  // `MemStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage`, `MemStage`  (STALL ITSELF)
  Seq(
    PipelineStageIndex.issueStage,
    PipelineStageIndex.regReadStage,
    PipelineStageIndex.exeStage,
    PipelineStageIndex.memStage
  )
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.memStallRequest)

  /** clear
    *
    * Assume A -> B, A is stall but B is not stall. Give A a clear signal to clear its output
    */

  Seq(
    PipelineStageIndex.issueStage,
    PipelineStageIndex.regReadStage,
    PipelineStageIndex.exeStage,
    PipelineStageIndex.memStage
  ).map(io.pipelineControlPorts(_)).reduce { (prev, next) =>
    prev.clear := prev.stall && !next.stall
    next
  }

  /** Exception
    */
  io.csrMessage := CuToCsrNdPort.default
  io.newPc      := zeroWord

  val linesHasException = WireDefault(VecInit(io.instInfoPorts.map(_.exceptionRecords.reduce(_ || _))))
  val hasException      = WireDefault(linesHasException.reduce(_ || _))

  /** Write Regfile
    */
  // temp
  io.gprWritePassThroughPorts.out(0) := Mux(
    hasException,
    io.gprWritePassThroughPorts.in(0),
    RfWriteNdPort.default
  )

  // 软件写csr
  io.csrWritePorts.zip(io.instInfoPorts).foreach {
    case (dst, src) =>
      dst := src.csrWritePort
  }

  /** flush
    */

  val flush = WireDefault(hasException)
  io.pipelineControlPorts.foreach(_.flush := flush)

  /** 硬件写csr
    */

  io.csrMessage.exceptionFlush := hasException
  // Attention: 由于encoder在全零的情况下会选择idx最高的那个，
  // 使用时仍需判断是否有exception
  val selectLineNum   = PriorityEncoder(linesHasException)
  val selectInstInfo  = io.instInfoPorts(selectLineNum)
  val selectException = PriorityEncoder(selectInstInfo.exceptionRecords)
  // 是否tlb重写异常
  val isTlbException = selectException(CsrRegs.ExceptionIndex.tlbr)

  // select era, ecodeBundle
  when(hasException) {
    io.csrMessage.era := selectInstInfo.pc
    switch(selectException) {
      is(CsrRegs.ExceptionIndex.int) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.int
      }
      is(CsrRegs.ExceptionIndex.pil) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.pil
      }
      is(CsrRegs.ExceptionIndex.pis) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.pis
      }
      is(CsrRegs.ExceptionIndex.pif) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.pif
      }
      is(CsrRegs.ExceptionIndex.pme) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.pme
      }
      is(CsrRegs.ExceptionIndex.ppi) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.ppi
      }
      is(CsrRegs.ExceptionIndex.adef) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.adef
      }
      is(CsrRegs.ExceptionIndex.adem) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.adem
      }
      is(CsrRegs.ExceptionIndex.ale) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.ale
      }
      is(CsrRegs.ExceptionIndex.sys) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.sys
      }
      is(CsrRegs.ExceptionIndex.brk) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.brk
      }
      is(CsrRegs.ExceptionIndex.ine) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.ine
      }
      is(CsrRegs.ExceptionIndex.ipe) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.ipe
      }
      is(CsrRegs.ExceptionIndex.fpd) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.fpd
      }
      is(CsrRegs.ExceptionIndex.fpe) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.fpe
      }
      is(CsrRegs.ExceptionIndex.tlbr) {
        io.csrMessage.ecodeBunle := CsrRegs.Estat.tlbr
      }
    }
  }

  // select new pc
  when(hasException) {
    when(isTlbException) {
      io.newPc := io.csrValues.tlbrentry
    }.otherwise {
      io.newPc := io.csrValues.eentry
    }

  }

  // etrn flush (完成异常？)
  val extnFlush = WireDefault(false.B) // 指令控制，待实现
  io.csrMessage.etrnFlush := extnFlush

  when(extnFlush) {
    io.newPc := io.csrValues.era
  }
}
