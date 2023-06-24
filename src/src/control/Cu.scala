package control

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, PcSetPort, RfWriteNdPort}
import control.bundles.{CsrValuePort, CsrWriteNdPort, CuToCsrNdPort, StableCounterReadPort}
import control.enums.ExceptionPos
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
    // `Csr` -> `Cu`
    val csrValues = Input(new CsrValuePort)
    // `ExeStage` -> `Cu`
    val branchExe = Input(new PcSetPort)
    // `Rob` -> `Cu`
    val branchCommit = Input(Bool())
    // `Cu` -> `Pc`
    val newPc = Output(new PcSetPort)

    // `Cu` <-> `StableCounter`
    val stableCounterReadPort = Flipped(new StableCounterReadPort)

    // val exceptionFlush  = Output(Bool())
    // val branchFlushInfo = Output(new BranchFlushInfo)
    val frontendFlush = Output(Bool())
    val backendFlush  = Output(Bool())

    // <- `MemResStage`, `WbStage`
    val isExceptionValidVec = Input(Vec(3, Bool()))
    // -> `MemReqStage`
    val isAfterMemReqFlush = Output(Bool())

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
  io.newPc      := PcSetPort.default

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

  // tlb
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
        selectInstInfo.load.vaddr,
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

  /** Flush & jump
    */

  val ertnFlush = WireDefault(
    io.instInfoPorts.map { instInfo => instInfo.exeOp === ExeInst.Op.ertn && instInfo.isValid }.reduce(_ || _)
  )

  // Handle after memory request exception valid
  io.isAfterMemReqFlush := io.isExceptionValidVec.asUInt.orR

  io.csrMessage.ertnFlush := ertnFlush
  io.frontendFlush        := RegNext(hasException || io.branchExe.en, false.B)
  io.backendFlush         := RegNext(hasException || io.branchCommit, false.B)

  // select new pc
  when(hasException) {
    io.newPc.en     := true.B
    io.newPc.isIdle := false.B
    when(isTlbRefillException) {
      io.newPc.pcAddr := io.csrValues.tlbrentry.asUInt
    }.otherwise {
      io.newPc.pcAddr := io.csrValues.eentry.asUInt
    }
  }.elsewhen(io.branchExe.en) {
    io.newPc := io.branchExe
  }

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
