package pipeline.memory

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import control.enums.ExceptionPos
import memory.bundles.{TlbMaintenanceNdPort, TlbTransPort}
import memory.enums.TlbMemType
import pipeline.commit.bundles.InstInfoNdPort
import pipeline.common.BaseStage
import pipeline.memory.bundles.{CacheMaintenanceInstNdPort, MemCsrNdPort, MemRequestNdPort}
import pipeline.memory.enums.AddrTransType
import spec.Param.{isDiffTest, isNoPrivilege}
import spec.Value.Csr
import spec.Width

import scala.collection.immutable

class AddrTransNdPort extends Bundle {
  val isAtomicStore    = Bool()
  val memRequest       = new MemRequestNdPort
  val gprAddr          = UInt(Width.Reg.addr)
  val instInfo         = new InstInfoNdPort
  val tlbMaintenance   = new TlbMaintenanceNdPort
  val cacheMaintenance = new CacheMaintenanceInstNdPort
}

object AddrTransNdPort {
  def default: AddrTransNdPort = 0.U.asTypeOf(new AddrTransNdPort)
}

class AddrTransPeerPort extends Bundle {
  val csr            = Input(new MemCsrNdPort)
  val tlbTrans       = Flipped(new TlbTransPort)
  val tlbMaintenance = Output(new TlbMaintenanceNdPort)
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

  val tlbBlockingReg = RegInit(false.B)
  tlbBlockingReg := tlbBlockingReg

  // Fallback output
  out.instInfo                := selectedIn.instInfo
  out.gprAddr                 := selectedIn.gprAddr
  out.translatedMemReq        := selectedIn.memRequest
  out.cacheMaintenance        := selectedIn.cacheMaintenance
  out.isAtomicStore           := selectedIn.isAtomicStore
  out.isAtomicStoreSuccessful := selectedIn.memRequest.isValid
  out.isCached                := false.B // Fallback: Uncached

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
      if (isNoPrivilege) {
        transMode := AddrTransType.directMapping
      }
    }
  }

  // Translate address
  val isCacheMaintenance = selectedIn.cacheMaintenance.control.isInit ||
    selectedIn.cacheMaintenance.control.isCoherentByIndex ||
    selectedIn.cacheMaintenance.control.isCoherentByHit
  val isCanTlbException = selectedIn.memRequest.isValid || selectedIn.cacheMaintenance.control.isCoherentByHit
  val translatedAddr    = WireDefault(selectedIn.memRequest.addr)
  if (isDiffTest) {
    out.instInfo.load.get.paddr := Cat(translatedAddr(Width.Mem._addr - 1, 2), selectedIn.instInfo.load.get.vaddr(1, 0))
    out.instInfo.store.get.paddr := Cat(
      translatedAddr(Width.Mem._addr - 1, 2),
      selectedIn.instInfo.store.get.vaddr(1, 0)
    )
  }
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
  peer.tlbTrans.isValid  := !tlbBlockingReg
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := selectedIn.memRequest.addr
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      if (!isNoPrivilege) {
        translatedAddr               := peer.tlbTrans.physAddr
        out.translatedMemReq.isValid := !peer.tlbTrans.exception.valid && selectedIn.memRequest.isValid

        // Handle exception
        val exceptionIndex = peer.tlbTrans.exception.bits
        when(
          selectedIn.instInfo.exceptionPos === ExceptionPos.none && peer.tlbTrans.exception.valid && isCanTlbException
        ) {
          out.instInfo.exceptionPos    := ExceptionPos.backend
          out.instInfo.exceptionRecord := exceptionIndex

          tlbBlockingReg := true.B
        }
      }
    }
  }

  // Can use cache
  switch(peer.csr.crmd.datm) {
    is(Csr.Crmd.Datm.suc) {
      out.isCached := false.B
    }
    is(Csr.Crmd.Datm.cc) {
      out.isCached := true.B
    }
  }

  // Handle TLB maintenance
  peer.tlbMaintenance := selectedIn.tlbMaintenance
  when(tlbBlockingReg) {
    peer.tlbMaintenance.isFill       := false.B
    peer.tlbMaintenance.isWrite      := false.B
    peer.tlbMaintenance.isRead       := false.B
    peer.tlbMaintenance.isInvalidate := false.B
    peer.tlbMaintenance.isSearch     := false.B
  }
  when(selectedIn.instInfo.isTlb) {
    tlbBlockingReg := true.B
  }
  if (isNoPrivilege) {
    peer.tlbMaintenance := DontCare
    io.in.ready         := inReady
  }

  // Handle flush (actually is TLB maintenance done)
  when(io.isFlush) {
    tlbBlockingReg := false.B
  }

  // Submit result
  when(selectedIn.instInfo.isValid && !tlbBlockingReg) {
    resultOutReg.valid := true.B
  }
}
