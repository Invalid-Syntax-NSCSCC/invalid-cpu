package control

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, PcSetNdPort, RfWriteNdPort}
import control.bundles.{CsrValuePort, CsrWriteNdPort, CuToCsrNdPort, StableCounterReadPort}
import control.enums.ExceptionPos
import memory.bundles.TlbMaintenanceNdPort
import pipeline.commit.bundles.InstInfoNdPort
import spec.Param.isDiffTest
import spec.{Csr, ExeInst, Param}

// Note. Exception只从第0个提交
class Cu(
  writeNum:  Int = Param.csrWriteNum,
  commitNum: Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {

    /** 回写与异常处理
      */
    // `WbStage` -> `Cu` -> `Regfile`
    val gprWritePassThroughPorts = new PassThroughPort(Vec(commitNum, new RfWriteNdPort))
    val instInfoPorts            = Input(Vec(commitNum, new InstInfoNdPort))
    // `Cu` -> `Csr`, 软件写
    val csrWritePorts = Output(Vec(writeNum, new CsrWriteNdPort))
    // `Cu` -> `Csr`, 硬件写
    val csrMessage = Output(new CuToCsrNdPort)
    // `Cu` -> `Csr`, Should TLB maintenance write
    val tlbMaintenanceCsrWriteValid = Output(Bool())
    // `Cu` -> `Csr`, Should TLB exception write
    val tlbExceptionCsrWriteValidVec = Output(Vec(Param.Count.Tlb.transNum, Bool()))
    // `Csr` -> `Cu`
    val csrFlushRequest = Input(Bool())
    val csrValues       = Input(new CsrValuePort)
    // `ExeStage` -> `Cu`
    val branchExe = Input(new PcSetNdPort)
    // `Rob` -> `Cu`
    val branchCommit = Input(Bool())
    // `Cu` -> `Pc`
    val newPc = Output(new PcSetNdPort)

    // `Cu` <-> `StableCounter`
    val stableCounterReadPort = Flipped(new StableCounterReadPort)

    val frontendFlush = Output(Bool())
    val backendFlush  = Output(Bool())
    val idleFlush     = Output(Bool())

    // <- Out
    val hardWareInetrrupt = Input(UInt(8.W))

    val difftest = if (isDiffTest) {
      Some(Output(new Bundle {
        val cmt_ertn       = Output(Bool())
        val cmt_excp_flush = Output(Bool())
      }))
    } else None
  })

  /** Exception
    */
  io.csrMessage := CuToCsrNdPort.default

  val hasException = WireDefault(io.instInfoPorts(0).exceptionPos =/= ExceptionPos.none) && io.instInfoPorts(0).isValid

  /** stable counter
    */
  io.stableCounterReadPort.exeOp := io.instInfoPorts(0).exeOp

  /** Write Regfile
    */
  // temp
  io.gprWritePassThroughPorts.out.zip(io.gprWritePassThroughPorts.in).foreach {
    case (dst, src) =>
      dst := src
  }
  when(hasException) {
    io.gprWritePassThroughPorts.out.foreach(_ := RfWriteNdPort.default)
  }.elsewhen(io.stableCounterReadPort.isMatch) {
    io.gprWritePassThroughPorts.out(0).data := io.stableCounterReadPort.output
  }

  /** CSR
    */

  io.csrMessage.hardWareInetrrupt := io.hardWareInetrrupt

  // csr write by inst
  io.csrWritePorts.zip(io.instInfoPorts).foreach {
    case (dst, src) =>
      dst.en   := src.csrWritePort.en && src.isValid && !hasException
      dst.addr := src.csrWritePort.addr
      dst.data := src.csrWritePort.data
  }

  /** csr write by exception
    */

  io.csrMessage.exceptionFlush := hasException
  // Attention: 由于encoder在全零的情况下会选择idx最高的那个，
  // 使用时仍需判断是否有exception
  val selectInstInfo     = WireDefault(io.instInfoPorts(0))
  val selectException    = WireDefault(selectInstInfo.exceptionRecord)
  val selectExceptionPos = WireDefault(selectInstInfo.exceptionPos)
  // 是否tlb重写异常：优先级最低，由前面是否发生其他异常决定
  val isTlbRefillException = selectException === Csr.ExceptionIndex.tlbr

  // select era, ecodeBundle
  when(hasException) {
    io.csrMessage.era := selectInstInfo.pc
    switch(selectException) {
      is(Csr.ExceptionIndex.int) {
        io.csrMessage.ecodeBundle := Csr.Estat.int
      }
      is(Csr.ExceptionIndex.pil) {
        io.csrMessage.ecodeBundle := Csr.Estat.pil
      }
      is(Csr.ExceptionIndex.pis) {
        io.csrMessage.ecodeBundle := Csr.Estat.pis
      }
      is(Csr.ExceptionIndex.pif) {
        io.csrMessage.ecodeBundle := Csr.Estat.pif
      }
      is(Csr.ExceptionIndex.pme) {
        io.csrMessage.ecodeBundle := Csr.Estat.pme
      }
      is(Csr.ExceptionIndex.ppi) {
        io.csrMessage.ecodeBundle := Csr.Estat.ppi
      }
      is(Csr.ExceptionIndex.adef) {
        io.csrMessage.ecodeBundle := Csr.Estat.adef
      }
      is(Csr.ExceptionIndex.adem) {
        io.csrMessage.ecodeBundle := Csr.Estat.adem
      }
      is(Csr.ExceptionIndex.ale) {
        io.csrMessage.ecodeBundle := Csr.Estat.ale
      }
      is(Csr.ExceptionIndex.sys) {
        io.csrMessage.ecodeBundle := Csr.Estat.sys
      }
      is(Csr.ExceptionIndex.brk) {
        io.csrMessage.ecodeBundle := Csr.Estat.brk
      }
      is(Csr.ExceptionIndex.ine) {
        io.csrMessage.ecodeBundle := Csr.Estat.ine
      }
      is(Csr.ExceptionIndex.ipe) {
        io.csrMessage.ecodeBundle := Csr.Estat.ipe
      }
      is(Csr.ExceptionIndex.fpd) {
        io.csrMessage.ecodeBundle := Csr.Estat.fpd
      }
      is(Csr.ExceptionIndex.fpe) {
        io.csrMessage.ecodeBundle := Csr.Estat.fpe
      }
      is(Csr.ExceptionIndex.tlbr) {
        io.csrMessage.ecodeBundle := Csr.Estat.tlbr
      }
    }
  }

  // TLB refill exception
  io.csrMessage.tlbRefillException := isTlbRefillException

  // badv
  when(hasException) {
    when(selectException === Csr.ExceptionIndex.adef) {
      io.csrMessage.badVAddrSet.en   := true.B
      io.csrMessage.badVAddrSet.addr := selectInstInfo.pc
    }.elsewhen(
      VecInit(
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
    ) {
      io.csrMessage.badVAddrSet.en := true.B
      io.csrMessage.badVAddrSet.addr := Mux(
        selectExceptionPos === ExceptionPos.backend,
        selectInstInfo.vaddr,
        selectInstInfo.pc
      )
    }
  }

  // llbit control
  val line0Is_ll = WireDefault(io.instInfoPorts(0).exeOp === ExeInst.Op.ll)
  val line0Is_sc = WireDefault(io.instInfoPorts(0).exeOp === ExeInst.Op.sc)
  io.csrMessage.llbitSet.en := line0Is_ll || line0Is_sc
  // ll -> 1, sc -> 0
  io.csrMessage.llbitSet.setValue := line0Is_ll

  // Handle TLB maintenance
  val isTlbMaintenance = WireDefault(io.instInfoPorts.head.isTlb && io.instInfoPorts.head.isValid && !hasException)
  io.tlbMaintenanceCsrWriteValid := isTlbMaintenance

  // Handle TLB exception
  io.tlbExceptionCsrWriteValidVec.foreach(_ := false.B)
  val isTlbException = VecInit(
    Csr.ExceptionIndex.tlbr,
    Csr.ExceptionIndex.pil,
    Csr.ExceptionIndex.pis,
    Csr.ExceptionIndex.pif,
    Csr.ExceptionIndex.pme,
    Csr.ExceptionIndex.ppi
  ).contains(selectException)
  when(isTlbException) {
    switch(selectExceptionPos) {
      is(ExceptionPos.frontend) {
        io.tlbExceptionCsrWriteValidVec(1) := true.B
      }
      is(ExceptionPos.backend) {
        io.tlbExceptionCsrWriteValidVec(0) := true.B
      }
    }
  }

  /** Flush & jump
    */

  val ertnFlush = WireDefault(
    io.instInfoPorts.map { instInfo => instInfo.exeOp === ExeInst.Op.ertn && instInfo.isValid }.reduce(_ || _)
  )

  val cacopFlush = io.instInfoPorts.head.exeOp === ExeInst.Op.cacop &&
    io.instInfoPorts.head.isValid &&
    io.instInfoPorts.head.forbidParallelCommit

  val idleFlush = io.instInfoPorts.head.exeOp === ExeInst.Op.idle && io.instInfoPorts.head.isValid && !hasException

  io.csrMessage.ertnFlush := ertnFlush
  io.frontendFlush := hasException || io.branchExe.en || isTlbMaintenance || io.csrFlushRequest || cacopFlush || idleFlush
  io.backendFlush := RegNext(
    hasException || io.branchCommit || isTlbMaintenance || io.csrFlushRequest || cacopFlush || idleFlush,
    false.B
  )
  io.idleFlush := idleFlush

  // select new pc
  io.newPc       := PcSetNdPort.default
  io.newPc.en    := isTlbMaintenance || io.csrFlushRequest || hasException || io.branchExe.en || cacopFlush || idleFlush
  io.newPc.isTlb := isTlbMaintenance
  io.newPc.pcAddr := Mux(
    hasException,
    Mux(isTlbRefillException, io.csrValues.tlbrentry.asUInt, io.csrValues.eentry.asUInt),
    Mux(
      isTlbMaintenance || io.csrFlushRequest || cacopFlush || idleFlush,
      io.instInfoPorts.head.pc + 4.U,
      io.branchExe.pcAddr
    )
  )

  val is_softwareInt = io.instInfoPorts(0).isValid &&
    io.instInfoPorts(0).csrWritePort.en &&
    (io.instInfoPorts(0).csrWritePort.addr === Csr.Index.estat) &&
    io.instInfoPorts(0).csrWritePort.data(1, 0).orR

  io.difftest match {
    case Some(dt) =>
      dt.cmt_ertn       := RegNext(ertnFlush)
      dt.cmt_excp_flush := RegNext(hasException)
    case _ =>
  }
}
