package frontend.fetch

import chisel3._
import chisel3.util._
import memory.enums.TlbMemType
import memory.bundles.TlbTransPort
import pipeline.memory.enums.AddrTransType
import frontend.bundles.FetchCsrNdPort
import spec.Value.Csr
import spec.Width

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
  val hasSendException = RegInit(false.B)

  io.out.bits  := outReg
  io.out.valid := !hasSendException // when has an exception send one exception to backend and stall; otherwise keep send
  when(io.isFlush){
    hasSendException := false.B
  }.elsewhen(outReg.exception.valid) {
    hasSendException := true.B
  }

  val isLastSent = RegNext(true.B, true.B)

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
      outReg.exception.bits := peer.tlbTrans.exception.bits
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
  val transMode                = WireDefault(AddrTransType.direct) // Fallback: Direct translation
  val isDirectMappingWindowHit = VecInit(directMapVec.map(_.isHit)).asUInt.orR
  when(!peer.csr.crmd.da && peer.csr.crmd.pg) {
    when(isDirectMappingWindowHit) {
      transMode := AddrTransType.directMapping
    }.otherwise {
      transMode := AddrTransType.pageTableMapping
    }
  }

  // Translate address
  val translatedAddr = WireDefault(io.pc)
  outReg.translatedMemReq.addr := translatedAddr
  peer.tlbTrans.memType        := TlbMemType.fetch
  peer.tlbTrans.virtAddr       := io.pc
  peer.tlbTrans.isValid        := false.B
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := io.pc
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      peer.tlbTrans.isValid := true.B
      translatedAddr        := peer.tlbTrans.physAddr
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
  when(io.isFlush) {
    io.out.bits.translatedMemReq.isValid := false.B
    isLastSent                           := true.B
  }
}
