package frontend.fetch

import chisel3._
import chisel3.util._
import memory.enums.TlbMemType
import memory.bundles.TlbTransPort
import pipeline.memory.enums.AddrTransType
import frontend.bundles.{FetchCsrNdPort, FtqBlockBundle}
import spec.Value.Csr
import spec.{Param, Width}

class InstAddrTransPeerPort extends Bundle {
  val csr      = Input(new FetchCsrNdPort)
  val tlbTrans = Flipped(new TlbTransPort)
}

class InstAddrTransStage extends Module {
  val io = IO(new Bundle {
    val isFlush       = Input(Bool())
    val ftqBlock      = Input(new FtqBlockBundle)
    val ftqId         = Input(UInt(Param.BPU.ftqPtrWitdh.W))
    val isBlockPcNext = Output(Bool())
    val out           = Decoupled(new InstReqNdPort)
    val peer          = new InstAddrTransPeerPort
  })

  val peer = io.peer

  val pc = WireDefault(0.U(Width.inst))
  pc := io.ftqBlock.startPc
  val isAdef           = WireDefault(pc(1, 0).orR) // PC is not aligned
  val outReg           = RegInit(InstReqNdPort.default)
  val hasSentException = RegInit(false.B)
  hasSentException := hasSentException
  val isLastSent    = RegNext(true.B, true.B)
  val isOutputValid = RegNext(io.ftqBlock.isValid || !isLastSent, false.B)

  io.out.valid     := isOutputValid
  io.out.bits      := outReg
  io.isBlockPcNext := hasSentException

  // Fallback output
  outReg.ftqBlock                 := io.ftqBlock
  outReg.ftqId                    := io.ftqId
  outReg.translatedMemReq.isValid := (io.ftqBlock.isValid || !isLastSent) && !isAdef
  outReg.exception.valid          := isAdef
  outReg.exception.bits           := spec.Csr.ExceptionIndex.adef

  // Handle exception
  def handleException(): Unit = {
    val isExceptionValid = isAdef || peer.tlbTrans.exception.valid
    outReg.exception.valid := isExceptionValid
    when(peer.tlbTrans.exception.valid && !isAdef) {
      outReg.exception.bits := peer.tlbTrans.exception.bits
    }
    when(isExceptionValid) {
      hasSentException := true.B
    }
  }

  // DMW mapping
  val directMapVec = Wire(
    Vec(
      2,
      new Bundle {
        val isHit      = Bool()
        val mappedAddr = UInt(Width.Mem.addr)
      }
    )
  )

  directMapVec.zip(peer.csr.dmw).foreach {
    case (target, window) =>
      target.isHit := ((peer.csr.crmd.plv === Csr.Crmd.Plv.high && window.plv0) ||
        (peer.csr.crmd.plv === Csr.Crmd.Plv.low && window.plv3)) &&
        window.vseg === pc(pc.getWidth - 1, pc.getWidth - 3)
      target.mappedAddr := Cat(
        window.pseg,
        pc(pc.getWidth - 4, 0)
      )
  }

  // Select a translation mode
  val isDirectMappingWindowHit = VecInit(directMapVec.map(_.isHit)).asUInt.orR
  when(!peer.csr.crmd.da && peer.csr.crmd.pg) {
    when(isDirectMappingWindowHit) {
      transMode := AddrTransType.directMapping
    }.otherwise {
      transMode := AddrTransType.pageTableMapping
      if (isNoPrivilege) {
        transMode := AddrTransType.directMapping
      }
    }
  }

  // Translate address
  val translatedAddr = WireDefault(pc)
  outReg.translatedMemReq.addr := translatedAddr
  peer.tlbTrans.memType        := TlbMemType.fetch
  peer.tlbTrans.virtAddr       := pc
  peer.tlbTrans.isValid        := false.B
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := pc
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      peer.tlbTrans.isValid := true.B
      translatedAddr        := peer.tlbTrans.physAddr
      outReg.translatedMemReq.isValid := (io.ftqBlock.isValid || !isLastSent) &&
        !peer.tlbTrans.exception.valid && !isAdef

      handleException()
    }
  }

  // Can use cache
  outReg.translatedMemReq.isCached := true.B // Always cached
//  switch(peer.csr.crmd.datf) {
//    is(Csr.Crmd.Datm.suc) {
//      outReg.translatedMemReq.isCached := false.B
//    }
//    is(Csr.Crmd.Datm.cc) {
//      outReg.translatedMemReq.isCached := true.B
//    }
//  }

  // If next stage not ready, then wait until ready
  when(!io.out.ready && !io.isFlush) {
    outReg     := outReg
    isLastSent := false.B
  }

  // Handle flush
  val isLastFlushReg = RegNext(io.isFlush, false.B)
  when(io.isFlush) {
    outReg.exception.valid               := false.B
    outReg.translatedMemReq.isValid      := false.B
    io.out.bits.translatedMemReq.isValid := false.B
    isLastSent                           := true.B
    hasSentException                     := false.B
  }
  when(isLastFlushReg) {
    io.out.valid := false.B
  }
}
