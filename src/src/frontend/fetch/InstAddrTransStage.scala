package frontend.fetch

import chisel3._
import chisel3.util._
import frontend.bundles.FetchCsrNdPort
import memory.bundles.TlbTransPort
import memory.enums.TlbMemType
import pipeline.memory.enums.AddrTransType
import spec.Value.Csr
import spec.Width
import spec.Param._

class InstAddrTransPeerPort extends Bundle {
  val csr      = Input(new FetchCsrNdPort)
  val tlbTrans = Flipped(new TlbTransPort)
}

class InstAddrTransStage extends Module {
  val io = IO(new Bundle {
    val isFlush    = Input(Bool())
    val isPcUpdate = Input(Bool())
    val pc         = Input(UInt(Width.Mem.addr))
    val out        = Decoupled(new InstReqNdPort)
    val peer       = new InstAddrTransPeerPort
  })

  val peer = io.peer

  val outReg = RegInit(InstReqNdPort.default)
  val isAdef = WireDefault(io.pc(1, 0).orR) // pc not aline
  io.out.bits  := outReg
  io.out.valid := true.B // still send to backend

  io.out.valid := isOutputValid
  io.out.bits  := outReg

  val transMode = WireDefault(AddrTransType.direct) // Fallback: Direct translation
  val isAdef = WireDefault(
    io.pc(1, 0).orR ||
      (io.pc(31).asBool && peer.csr.crmd.plv === Csr.Crmd.Plv.low && transMode === AddrTransType.pageTableMapping)
  ) // PC is not aligned

  // Fallback output
  outReg.pc                       := io.pc
  outReg.translatedMemReq.isValid := (io.isPcUpdate || !isLastSent) && io.pc.orR && !isAdef
  outReg.exception.valid          := isAdef
  outReg.exception.bits           := spec.Csr.ExceptionIndex.adef

  // Handle exception
  def handleException(): Unit = {
    outReg.exception.valid := isAdef || peer.tlbTrans.exception.valid
    // exception priority: pif > ppi > adef > tlbr  bitsValue 0 as highest priority
    when(peer.tlbTrans.exception.valid && peer.tlbTrans.exception.bits < spec.Csr.ExceptionIndex.adef) {
      outReg.exception.bits := peer.tlbTrans.exception.valid
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
        window.vseg === io.pc(io.pc.getWidth - 1, io.pc.getWidth - 3)
      target.mappedAddr := Cat(
        window.pseg,
        io.pc(io.pc.getWidth - 4, 0)
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
  val translatedAddr = WireDefault(io.pc)
  outReg.translatedMemReq.addr := translatedAddr
  peer.tlbTrans.memType        := TlbMemType.fetch
  peer.tlbTrans.virtAddr       := io.pc
  peer.tlbTrans.isValid        := !hasSentException
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := io.pc
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      translatedAddr := peer.tlbTrans.physAddr
      outReg.translatedMemReq.isValid := (io.isPcUpdate || !isLastSent) &&
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
