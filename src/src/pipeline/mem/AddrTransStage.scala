package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import common.enums.ReadWriteSel
import control.bundles.PipelineControlNdPort
import memory.bundles.TlbTransPort
import memory.enums.TlbMemType
import pipeline.common.BaseStage
import pipeline.mem.bundles.{MemCsrNdPort, MemRequestNdPort}
import pipeline.mem.enums.AddrTransType
import pipeline.commit.bundles.InstInfoNdPort
import spec.Value.Csr
import spec.Width

import scala.collection.immutable
import control.enums.ExceptionPos

class AddrTransNdPort extends Bundle {
  val memRequest = new MemRequestNdPort
  val gprAddr    = UInt(Width.Reg.addr)
  val instInfo   = new InstInfoNdPort
}

object AddrTransNdPort {
  def default: AddrTransNdPort = 0.U.asTypeOf(new AddrTransNdPort)
}

class AddrTransPeerPort extends Bundle {
  val csr      = Input(new MemCsrNdPort)
  val tlbTrans = Flipped(new TlbTransPort)
}

class AddrTransStage
    extends BaseStage(
      new AddrTransNdPort,
      new MemReqNdPort,
      AddrTransNdPort.default,
      Some(new AddrTransPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  // Fallback output
  out.instInfo         := selectedIn.instInfo
  out.gprAddr          := selectedIn.gprAddr
  out.translatedMemReq := selectedIn.memRequest
  out.isCached         := false.B // Fallback: Uncached

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
        window.vseg === selectedIn.memRequest
          .addr(selectedIn.memRequest.addr.getWidth - 1, selectedIn.memRequest.addr.getWidth - 3)
      target.mappedAddr := Cat(
        window.pseg,
        selectedIn.memRequest.addr(selectedIn.memRequest.addr.getWidth - 4, 0)
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
  val translatedAddr = WireDefault(selectedIn.memRequest.addr)
  out.instInfo.load.paddr   := Cat(translatedAddr(Width.Mem._addr - 1, 2), selectedIn.instInfo.load.vaddr(1, 0))
  out.instInfo.store.paddr  := Cat(translatedAddr(Width.Mem._addr - 1, 2), selectedIn.instInfo.store.vaddr(1, 0))
  out.translatedMemReq.addr := translatedAddr
  peer.tlbTrans.memType := MuxLookup(
    selectedIn.memRequest.rw,
    TlbMemType.load
  )(
    immutable.Seq(
      ReadWriteSel.read -> TlbMemType.load,
      ReadWriteSel.write -> TlbMemType.store
    )
  )
  peer.tlbTrans.virtAddr := selectedIn.memRequest.addr
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := selectedIn.memRequest.addr
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      translatedAddr               := peer.tlbTrans.physAddr
      out.translatedMemReq.isValid := selectedIn.memRequest.isValid && !peer.tlbTrans.exception.valid

      val exceptionIndex = peer.tlbTrans.exception.bits
      out.instInfo.exceptionPos := selectedIn.instInfo.exceptionPos
      when(selectedIn.instInfo.exceptionPos =/= ExceptionPos.none) {
        when(peer.tlbTrans.exception.valid) {
          out.instInfo.exceptionPos    := ExceptionPos.backend
          out.instInfo.exceptionRecord := exceptionIndex
        }
      }
    }
  }

  // TODO: CSR write for TLB maintenance

  // Can use cache
  switch(peer.csr.crmd.datm) {
    is(Csr.Crmd.Datm.suc) {
      out.isCached := false.B
    }
    is(Csr.Crmd.Datm.cc) {
      out.isCached := true.B
    }
  }

  // Submit result
  when(selectedIn.instInfo.isValid) {
    resultOutReg.valid := true.B
  }
}
