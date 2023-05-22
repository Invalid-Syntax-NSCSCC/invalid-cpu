package control

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import control.bundles.PipelineControlNDPort
import spec.PipelineStageIndex
import pipeline.writeback.bundles.InstInfoNdPort
import common.bundles.RfWriteNdPort
import common.bundles.PassThroughPort
import control.bundles.CsrWriteNdPort
import control.bundles.CuToCsrNdPort
import spec.Csr
import control.bundles.CsrToCuNdPort
import spec.Width
import spec.zeroWord
import spec.ExeInst
import common.bundles.PcSetPort
import control.bundles.StableCounterReadPort

// TODO: Add stall to frontend ?
// TODO: Add deal exceptions
// TODO: 出错虚地址badv的赋值
// TODO: 处理break和syscall指令
// 优先解决多发射中index小的流水线
// Attention: ll与sc指令只能从第0条流水线发射（按满洋的设计）
class Cu(
  ctrlControlNum: Int = Param.ctrlControlNum,
  writeNum:       Int = Param.csrRegsWriteNum,
  dispatchNum:    Int = Param.dispatchInstNum)
    extends Module {
  val io = IO(new Bundle {

    /** 回写与异常处理
      */
    // `WbStage` -> `Cu` -> `Regfile`
    val gprWritePassThroughPorts = new PassThroughPort(Vec(dispatchNum, new RfWriteNdPort))
    val instInfoPorts            = Input(Vec(dispatchNum, new InstInfoNdPort))
    // `Cu` -> `Csr`, 软件写
    val csrWritePorts = Output(Vec(writeNum, new CsrWriteNdPort))
    // `Cu` -> `Csr`, 硬件写
    val csrMessage = Output(new CuToCsrNdPort)
    // `Csr` -> `Cu`
    val csrValues = Input(new CsrToCuNdPort)
    // `ExeStage` -> `Cu`
    val jumpPc = Input(new PcSetPort)
    // `Csr` -> `Pc`
    val newPc = Output(new PcSetPort)

    // `Cu` <-> `StableCounter`
    val stableCounterReadPort = Flipped(new StableCounterReadPort)

    /** 暂停信号
      */
    // `ExeStage` -> `Cu`
    val exeStallRequest = Input(Bool())
    // `AddrTransStage` -> `Cu`
    val memStallRequest = Input(Bool())
    // `Cu` -> `IssueStage`, `RegReadStage`, `ExeStage`, `AddrTransStage`
    val pipelineControlPorts = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
  })

  /** Stall 暂停流水线前面部分
    */

  io.pipelineControlPorts.foreach(_ := PipelineControlNDPort.default)
  // `ExeStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage` (STALL ITSELF)
  Seq(PipelineStageIndex.issueStage, PipelineStageIndex.regReadStage, PipelineStageIndex.exeStage)
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.exeStallRequest)
  // `AddrTransStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage`, `AddrTransStage`  (STALL ITSELF)
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
  io.newPc      := PcSetPort.default

  val linesHasException = WireDefault(VecInit(io.instInfoPorts.map(_.exceptionRecords.reduce(_ || _))))
  val hasException      = WireDefault(linesHasException.reduce(_ || _))

  /** stable counter
    */
  io.stableCounterReadPort.exeOp := io.instInfoPorts(0).exeOp

  /** Write Regfile
    */
  // temp
  io.gprWritePassThroughPorts.out(0) := io.gprWritePassThroughPorts.in(0)
  when(hasException) {
    io.gprWritePassThroughPorts.out(0) := RfWriteNdPort.default
  }.elsewhen(io.stableCounterReadPort.isMatch) {
    io.gprWritePassThroughPorts.out(0).data := io.stableCounterReadPort.output
  }

  // 软件写csr
  io.csrWritePorts.zip(io.instInfoPorts).foreach {
    case (dst, src) =>
      dst := src.csrWritePort
  }

  /** flush
    */

  when(io.jumpPc.en) {
    Seq(
      PipelineStageIndex.issueStage,
      PipelineStageIndex.regReadStage,
      PipelineStageIndex.instQueue
    ).map(io.pipelineControlPorts(_))
      .foreach(_.flush := true.B)
  }

  val exceptionFlush = WireDefault(hasException)
  when(exceptionFlush) {
    io.pipelineControlPorts.foreach(_.flush := true.B)
  }

  /** 硬件写csr
    */

  io.csrMessage.exceptionFlush := hasException
  // Attention: 由于encoder在全零的情况下会选择idx最高的那个，
  // 使用时仍需判断是否有exception
  val selectLineNum          = PriorityEncoder(linesHasException)
  val selectInstInfo         = WireDefault(io.instInfoPorts(selectLineNum))
  val selectException        = PriorityEncoder(selectInstInfo.exceptionRecords)
  val selectExceptionAsBools = selectException.asBools
  // 是否tlb重写异常：优先级最低，由前面是否发生其他异常决定
  val isTlbRefillException = !selectExceptionAsBools.take(selectExceptionAsBools.length - 1).reduce(_ || _)

  // select era, ecodeBundle
  when(hasException) {
    io.csrMessage.era := selectInstInfo.pc
    switch(selectException) {
      is(Csr.ExceptionIndex.int) {
        io.csrMessage.ecodeBunle := Csr.Estat.int
      }
      is(Csr.ExceptionIndex.pil) {
        io.csrMessage.ecodeBunle := Csr.Estat.pil
      }
      is(Csr.ExceptionIndex.pis) {
        io.csrMessage.ecodeBunle := Csr.Estat.pis
      }
      is(Csr.ExceptionIndex.pif) {
        io.csrMessage.ecodeBunle := Csr.Estat.pif
      }
      is(Csr.ExceptionIndex.pme) {
        io.csrMessage.ecodeBunle := Csr.Estat.pme
      }
      is(Csr.ExceptionIndex.ppi) {
        io.csrMessage.ecodeBunle := Csr.Estat.ppi
      }
      is(Csr.ExceptionIndex.adef) {
        io.csrMessage.ecodeBunle := Csr.Estat.adef
      }
      is(Csr.ExceptionIndex.adem) {
        io.csrMessage.ecodeBunle := Csr.Estat.adem
      }
      is(Csr.ExceptionIndex.ale) {
        io.csrMessage.ecodeBunle := Csr.Estat.ale
      }
      is(Csr.ExceptionIndex.sys) {
        io.csrMessage.ecodeBunle := Csr.Estat.sys
      }
      is(Csr.ExceptionIndex.brk) {
        io.csrMessage.ecodeBunle := Csr.Estat.brk
      }
      is(Csr.ExceptionIndex.ine) {
        io.csrMessage.ecodeBunle := Csr.Estat.ine
      }
      is(Csr.ExceptionIndex.ipe) {
        io.csrMessage.ecodeBunle := Csr.Estat.ipe
      }
      is(Csr.ExceptionIndex.fpd) {
        io.csrMessage.ecodeBunle := Csr.Estat.fpd
      }
      is(Csr.ExceptionIndex.fpe) {
        io.csrMessage.ecodeBunle := Csr.Estat.fpe
      }
      is(Csr.ExceptionIndex.tlbr) {
        io.csrMessage.ecodeBunle := Csr.Estat.tlbr
      }
    }
  }
  // etrn flush (完成异常？)
  val extnFlush = WireDefault(
    io.instInfoPorts.map(_.exeOp === ExeInst.Op.ertn).reduce(_ || _)
  ) // 指令控制
  io.csrMessage.ertnFlush := extnFlush

  // select new pc
  when(extnFlush) {
    io.newPc.en     := true.B
    io.newPc.pcAddr := io.csrValues.era
  }.elsewhen(hasException) {
    io.newPc.en := true.B
    when(isTlbRefillException) {
      io.newPc.pcAddr := io.csrValues.tlbrentry
    }.otherwise {
      io.newPc.pcAddr := io.csrValues.eentry
    }
  }.elsewhen(io.jumpPc.en) {
    io.newPc := io.jumpPc
  }

  // tlb
  io.csrMessage.tlbRefillException := isTlbRefillException

  // badv
  // TODO: 记录出错的虚地址，多数情况不是pc，待补
  io.csrMessage.badVAddrSet.en := VecInit(
    Csr.ExceptionIndex.tlbr,
    Csr.ExceptionIndex.adef,
    Csr.ExceptionIndex.adem,
    Csr.ExceptionIndex.ale,
    Csr.ExceptionIndex.pil,
    Csr.ExceptionIndex.pis,
    Csr.ExceptionIndex.pif,
    Csr.ExceptionIndex.pme,
    Csr.ExceptionIndex.ppi
  ).contains(selectException)
  io.csrMessage.badVAddrSet.addr := selectInstInfo.pc

  // llbit control
  val line0Is_ll = WireDefault(io.instInfoPorts(0).exeOp === ExeInst.Op.ll)
  val line0Is_sc = WireDefault(io.instInfoPorts(0).exeOp === ExeInst.Op.sc)
  io.csrMessage.llbitSet.en := line0Is_ll || line0Is_sc
  // ll -> 1, sc -> 0
  io.csrMessage.llbitSet.setValue := line0Is_ll
}
