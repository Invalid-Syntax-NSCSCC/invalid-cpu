package control

import chisel3._
import chisel3.util._
import common.bundles.{BackendRedirectPcNdPort, PassThroughPort, RfWriteNdPort}
import control.bundles.{CsrValuePort, CsrWriteNdPort, CuToCsrNdPort, StableCounterReadPort}
import control.enums.ExceptionPos
import pipeline.commit.bundles.InstInfoNdPort
import spec.Param.isDiffTest
import spec.{Csr, ExeInst, Param}
import frontend.bundles.CuCommitFtqPort

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
    val branchExe = Input(new BackendRedirectPcNdPort)
    // `Rob` -> `Cu`
    val branchCommit = Input(Bool())
    // `CsrScoreBoard` -> `Cu`
    val csrWriteInfo = Input(new CsrWriteNdPort)
    val newPc        = Output(new BackendRedirectPcNdPort)

    val frontendFlush      = Output(Bool())
    val frontendFlushFtqId = Output(UInt(Param.BPU.ftqPtrWitdh.W))
    val backendFlush       = Output(Bool())
    val idleFlush          = Output(Bool())

    val ftqPort = Flipped(new CuCommitFtqPort)

    // <- Out
    val hardwareInterrupt = Input(UInt(8.W))

    val difftest = if (isDiffTest) {
      Some(Output(new Bundle {
        val cmt_ertn       = Output(Bool())
        val cmt_excp_flush = Output(Bool())
      }))
    } else None
  })

  // Fallback
  io.csrMessage := CuToCsrNdPort.default

  // Values
  val majorInstInfo = io.instInfoPorts.head
  val isException   = (majorInstInfo.exceptionPos =/= ExceptionPos.none) && majorInstInfo.isValid

  // Write GPR
  io.gprWritePassThroughPorts.out.zip(io.gprWritePassThroughPorts.in).foreach {
    case (dst, src) =>
      dst := src
  }
  when(isException) {
    io.gprWritePassThroughPorts.out.foreach(_.en := false.B)
  }

  // Hardware interrupt
  io.csrMessage.hardwareInterrupt := io.hardwareInterrupt

  // CSR write by instruction
  io.csrWritePorts.head.en   := majorInstInfo.isCsrWrite && majorInstInfo.isValid && !isException && io.csrWriteInfo.en
  io.csrWritePorts.head.addr := io.csrWriteInfo.addr
  io.csrWritePorts.head.data := io.csrWriteInfo.data

  io.csrMessage.exceptionFlush := isException
  // Attention: 由于encoder在全零的情况下会选择idx最高的那个，
  // 使用时仍需判断是否有exception
  // 是否tlb重写异常：优先级最低，由前面是否发生其他异常决定

  // select era, ecodeBundle
  when(isException) {
    io.csrMessage.era := majorInstInfo.pc
    switch(majorInstInfo.exceptionRecord) {
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
  io.csrMessage.tlbRefillException := isException && majorInstInfo.exceptionRecord === Csr.ExceptionIndex.tlbr

  // badv
  when(isException) {
    when(majorInstInfo.exceptionRecord === Csr.ExceptionIndex.adef) {
      io.csrMessage.badVAddrSet.en   := true.B
      io.csrMessage.badVAddrSet.addr := majorInstInfo.pc
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
      ).contains(majorInstInfo.exceptionRecord)
    ) {
      io.csrMessage.badVAddrSet.en := true.B
      io.csrMessage.badVAddrSet.addr := Mux(
        majorInstInfo.exceptionPos === ExceptionPos.backend,
        majorInstInfo.vaddr,
        majorInstInfo.pc
      )
    }
  }

  // llbit control
  val isLoadLinked       = WireDefault(majorInstInfo.exeOp === ExeInst.Op.ll)
  val isStoreConditional = WireDefault(majorInstInfo.exeOp === ExeInst.Op.sc)
  io.csrMessage.llbitSet.en := (isLoadLinked || isStoreConditional) && majorInstInfo.isValid && !isException
  // ll -> 1, sc -> 0
  io.csrMessage.llbitSet.setValue := isLoadLinked

  // Handle TLB maintenance
  val isTlbMaintenance = majorInstInfo.isTlb && majorInstInfo.isValid && !isException
  io.tlbMaintenanceCsrWriteValid := RegNext(isTlbMaintenance, false.B)

  // Handle TLB exception
  io.tlbExceptionCsrWriteValidVec.foreach(_ := false.B)
  val isTlbException = VecInit(
    Csr.ExceptionIndex.tlbr,
    Csr.ExceptionIndex.pil,
    Csr.ExceptionIndex.pis,
    Csr.ExceptionIndex.pif,
    Csr.ExceptionIndex.pme,
    Csr.ExceptionIndex.ppi
  ).contains(majorInstInfo.exceptionRecord) && isException
  when(isTlbException) {
    switch(majorInstInfo.exceptionPos) {
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

  val isExceptionReturn = majorInstInfo.exeOp === ExeInst.Op.ertn && majorInstInfo.isValid && !isException

  val cacopFlush = majorInstInfo.exeOp === ExeInst.Op.cacop && majorInstInfo.isValid

  val idleFlush = majorInstInfo.exeOp === ExeInst.Op.idle && majorInstInfo.isValid && !isException

  io.csrMessage.ertnFlush := isExceptionReturn // TODO: Make ERTN jump gracefully like branch instruction
  io.frontendFlush :=
    RegNext(
      isException || io.branchExe.en || isTlbMaintenance || io.csrFlushRequest || cacopFlush || idleFlush || isExceptionReturn,
      false.B
    )
  val frontendFlushFtqId = WireDefault(
    Mux(
      isException || isTlbMaintenance || io.csrFlushRequest || cacopFlush || idleFlush || isExceptionReturn,
      majorInstInfo.ftqInfo.ftqId,
      io.branchExe.ftqId
    )
  )
  io.frontendFlushFtqId := RegNext(frontendFlushFtqId)
  io.backendFlush := RegNext(
    isException || io.branchCommit || isTlbMaintenance || io.csrFlushRequest || cacopFlush || idleFlush || isExceptionReturn,
    false.B
  )
  io.idleFlush := RegNext(idleFlush)

  // Select new pc
  io.newPc := BackendRedirectPcNdPort.default
  io.newPc.en :=
    isTlbMaintenance || io.csrFlushRequest || isException || io.branchExe.en || cacopFlush || idleFlush || isExceptionReturn
  // io.newPc.isTlb := isTlbMaintenance

  io.newPc.ftqId := Mux(
    isTlbMaintenance || io.csrFlushRequest || isException || cacopFlush || idleFlush || isExceptionReturn,
    majorInstInfo.ftqInfo.ftqId,
    io.branchExe.ftqId
  )

  io.newPc.pcAddr := Mux(
    isException,
    Mux(
      majorInstInfo.exceptionRecord === Csr.ExceptionIndex.tlbr,
      io.csrValues.tlbrentry.asUInt,
      io.csrValues.eentry.asUInt
    ),
    Mux(
      isExceptionReturn,
      io.csrValues.era.pc,
      Mux(
        isTlbMaintenance || io.csrFlushRequest || cacopFlush || idleFlush,
        majorInstInfo.pc + 4.U,
        io.branchExe.pcAddr
      )
    )
  )

  // BPU training data
  io.ftqPort.ftqId               := majorInstInfo.ftqInfo.ftqId
  io.ftqPort.meta.isBranch       := majorInstInfo.ftqCommitInfo.isBranch
  io.ftqPort.meta.isTaken        := majorInstInfo.ftqCommitInfo.isBranchSuccess
  io.ftqPort.meta.predictedTaken := majorInstInfo.ftqInfo.predictBranch
  io.ftqPort.meta.branchType     := majorInstInfo.ftqCommitInfo.branchType

  io.ftqPort.queryPcBundle.ftqId := majorInstInfo.ftqInfo.ftqId

  io.ftqPort.bitMask.foreach(_ := false.B)
  io.ftqPort.bitMask.lazyZip(io.instInfoPorts).zipWithIndex.foreach {
    case ((mask, instInfo), idx) =>
      if (idx == 0) {
        instInfo.isValid && (isException || instInfo.ftqInfo.isLastInBlock)
      } else {
        instInfo.isValid && instInfo.ftqInfo.isLastInBlock
      }
  }

  io.ftqPort.blockBitmask.lazyZip(io.instInfoPorts).zipWithIndex.foreach {
    case ((mask, instInfo), idx) =>
      if (idx == 0) {
        mask := instInfo.isValid && instInfo.ftqInfo.isLastInBlock
      } else {
        mask := instInfo.isValid && instInfo.ftqInfo.isLastInBlock
      }
  }

  io.difftest match {
    case Some(dt) =>
      dt.cmt_ertn       := RegNext(isExceptionReturn)
      dt.cmt_excp_flush := RegNext(isException)
    case _ =>
  }
}
