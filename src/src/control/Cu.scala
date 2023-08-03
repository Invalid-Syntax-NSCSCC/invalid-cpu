package control

import chisel3._
import chisel3.util._
import common.bundles.{BackendRedirectPcNdPort, PassThroughPort, RfWriteNdPort}
import control.bundles.{CsrValuePort, CsrWriteNdPort, CuToCsrNdPort}
import control.enums.ExceptionPos
import frontend.bundles.{CuCommitFtqNdPort, QueryPcBundle}
import pipeline.common.bundles.InstInfoNdPort
import spec.Param.isDiffTest
import spec.{Csr, ExeInst, Param, Width}

// Note. Exception只从第0个提交
class Cu(
  writeNum:  Int = Param.csrWriteNum,
  commitNum: Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {

    // `WbStage` -> `Cu` -> `Regfile`
    val gprWritePassThroughPorts = new PassThroughPort(Vec(commitNum, new RfWriteNdPort))
    val instInfoPorts            = Input(Vec(commitNum, new InstInfoNdPort))
    val majorPc                  = Input(UInt(Width.Reg.data))
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
    // `MultiInstQueue` -> `Cu`
    val redirectFromDecode = Input(new BackendRedirectPcNdPort)
    // `CsrScoreBoard` -> `Cu`
    val csrWriteInfo = Input(new CsrWriteNdPort)
    val newPc        = Output(new BackendRedirectPcNdPort)

    val isBranchFlush      = Output(Bool())
    val frontendFlush      = Output(Bool())
    val frontendFlushFtqId = Output(UInt(Param.BPU.ftqPtrWidth.W))
    val backendFlush       = Output(Bool())
    val idleFlush          = Output(Bool())

    val ftqPort     = Output(new CuCommitFtqNdPort)
    val queryPcPort = Flipped(new QueryPcBundle)

    val exceptionVirtAddr = Input(UInt(Width.Mem.addr))

    val isDbarFinish = Output(Bool())

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
  io.queryPcPort.ftqId := majorInstInfo.ftqInfo.ftqId
  val majorPc     = io.majorPc // : UInt = WireDefault(io.queryPcPort.pc + (majorInstInfo.ftqInfo.idxInBlock << 2))
  val isException = (majorInstInfo.exceptionPos =/= ExceptionPos.none) && majorInstInfo.isValid

  // Write GPR
  io.gprWritePassThroughPorts.out.zip(io.gprWritePassThroughPorts.in).foreach {
    case (dst, src) =>
      dst := src
  }
  when(isException) {
    io.gprWritePassThroughPorts.out.foreach(_.en := false.B)
  }

  // CSR write by instruction
  io.csrWritePorts.head.en   := majorInstInfo.isCsrWrite && majorInstInfo.isValid && !isException && io.csrWriteInfo.en
  io.csrWritePorts.head.addr := io.csrWriteInfo.addr
  io.csrWritePorts.head.data := io.csrWriteInfo.data

  io.csrMessage.exceptionFlush := isException

  // select era, ecodeBundle
  when(isException) {
    io.csrMessage.era := majorPc
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
    when(
      VecInit(
        Csr.ExceptionIndex.tlbr,
        Csr.ExceptionIndex.adef,
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
        io.exceptionVirtAddr,
        majorPc
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

  // Flush & jump

  val redirectCommit = majorInstInfo.ftqCommitInfo.isRedirect && majorInstInfo.isValid

  val isExceptionReturn = majorInstInfo.exeOp === ExeInst.Op.ertn && majorInstInfo.isValid && !isException

  val idleFlush = majorInstInfo.exeOp === ExeInst.Op.idle && majorInstInfo.isValid && !isException

  // need refetch : tlb ; csr change ; cacop ; idle
  val refetchFlush = majorInstInfo.isValid && majorInstInfo.needRefetch

  io.csrMessage.ertnFlush := isExceptionReturn
  io.frontendFlush :=
    RegNext(
      isException || io.branchExe.en || io.redirectFromDecode.en || refetchFlush || isExceptionReturn,
      false.B
    )
  val frontendFlushFtqId = Mux(
    isException || refetchFlush || isExceptionReturn,
    majorInstInfo.ftqInfo.ftqId,
    Mux(io.branchExe.en, io.branchExe.ftqId, io.redirectFromDecode.ftqId)
  )
  io.frontendFlushFtqId := RegNext(frontendFlushFtqId, 0.U)
  io.backendFlush := RegNext(
    isException || redirectCommit || refetchFlush || isExceptionReturn,
    false.B
  )
  io.idleFlush     := RegNext(idleFlush, false.B)
  io.isBranchFlush := RegNext(io.branchExe.en, false.B)

  // Select new pc
  val refetchFlushDelay       = RegNext(refetchFlush, false.B)
  val isExceptionDelay        = RegNext(isException, false.B)
  val redirectFromExeDelay    = RegNext(io.branchExe, BackendRedirectPcNdPort.default)
  val redirectFromDecodeDelay = RegNext(io.redirectFromDecode, BackendRedirectPcNdPort.default)
  val isExceptionReturnDelay  = RegNext(isExceptionReturn, false.B)
  io.newPc.en := refetchFlushDelay ||
    isExceptionDelay ||
    redirectFromExeDelay.en ||
    redirectFromDecodeDelay.en ||
    isExceptionReturnDelay

  io.newPc.ftqId := Mux(
    refetchFlushDelay || isExceptionDelay || isExceptionReturnDelay,
    RegNext(majorInstInfo.ftqInfo.ftqId, 0.U),
    Mux(redirectFromExeDelay.en, redirectFromExeDelay.ftqId, redirectFromDecodeDelay.ftqId)
  )

  io.newPc.pcAddr := Mux(
    isExceptionDelay,
    RegNext(
      Mux(
        majorInstInfo.exceptionRecord === Csr.ExceptionIndex.tlbr,
        io.csrValues.tlbrentry.asUInt,
        io.csrValues.eentry.asUInt
      ),
      0.U
    ),
    Mux(
      isExceptionReturnDelay,
      RegNext(io.csrValues.era.pc, 0.U),
      Mux(
        refetchFlushDelay,
        RegNext(majorPc + 4.U, 0.U),
        Mux(redirectFromExeDelay.en, redirectFromExeDelay.pcAddr, redirectFromDecodeDelay.pcAddr)
      )
    )
  )

  // BPU training data
  val ftqCommitInfo = RegInit(CuCommitFtqNdPort.default)
  io.ftqPort := ftqCommitInfo

  ftqCommitInfo.ftqId               := majorInstInfo.ftqInfo.ftqId
  ftqCommitInfo.meta.isBranch       := majorInstInfo.ftqCommitInfo.isBranch && majorInstInfo.isValid
  ftqCommitInfo.meta.isTaken        := majorInstInfo.ftqCommitInfo.isBranchSuccess
  ftqCommitInfo.meta.predictedTaken := majorInstInfo.ftqInfo.predictBranch
  ftqCommitInfo.meta.branchType     := majorInstInfo.ftqCommitInfo.branchType

  ftqCommitInfo.bitMask.foreach(_ := false.B)
  ftqCommitInfo.bitMask.lazyZip(io.instInfoPorts).zipWithIndex.foreach {
    case ((mask, instInfo), idx) =>
      if (idx == 0) {
        mask := instInfo.isValid && (isException ||
          instInfo.ftqInfo.isLastInBlock ||
          refetchFlush ||
          isExceptionReturn)
      } else {
        mask := instInfo.isValid && instInfo.ftqInfo.isLastInBlock
      }
  }

  ftqCommitInfo.blockBitmask.lazyZip(io.instInfoPorts).zipWithIndex.foreach {
    case ((mask, instInfo), idx) =>
      if (idx == 0) {
        mask := instInfo.isValid && instInfo.ftqInfo.isLastInBlock
      } else {
        mask := instInfo.isValid && instInfo.ftqInfo.isLastInBlock
      }
  }

  io.isDbarFinish := io.instInfoPorts.map { instInfo =>
    instInfo.isValid && instInfo.exeOp === ExeInst.Op.dbar
  }.reduce(_ || _)

  io.difftest match {
    case Some(dt) =>
      dt.cmt_ertn       := RegNext(isExceptionReturn, false.B)
      dt.cmt_excp_flush := RegNext(isException, false.B)
    case _ =>
  }
}
