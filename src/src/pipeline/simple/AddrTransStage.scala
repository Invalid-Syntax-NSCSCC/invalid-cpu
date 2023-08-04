package pipeline.simple

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import common.{BaseStage, NoSavedInBaseStage}
import control.enums.ExceptionPos
import memory.bundles.{TlbMaintenanceNdPort, TlbTransPort}
import memory.enums.TlbMemType
import pipeline.common.bundles.{CacheMaintenanceInstNdPort, MemCsrNdPort, MemRequestNdPort}
import pipeline.common.enums.AddrTransType
import pipeline.simple.bundles.WbNdPort
import spec.Param.{isCacheOnPg, isDiffTest, isForcedCache, isForcedUncached, isNoPrivilege}
import spec.Value.Csr
import spec._

import scala.collection.immutable

class AddrTransNdPort extends Bundle {
  val isAtomicStore    = Bool()
  val memRequest       = new MemRequestNdPort
  val tlbMaintenance   = new TlbMaintenanceNdPort
  val cacheMaintenance = new CacheMaintenanceInstNdPort
  val wb               = new WbNdPort
}

object AddrTransNdPort {
  def default = 0.U.asTypeOf(new AddrTransNdPort)
}

class AddrTransPeerPort extends Bundle {
  val csr               = Input(new MemCsrNdPort)
  val tlbTrans          = Flipped(new TlbTransPort)
  val tlbMaintenance    = Output(new TlbMaintenanceNdPort)
  val exceptionVirtAddr = Output(UInt(Width.Mem.addr))
}

class AddrTransStage
    extends NoSavedInBaseStage(
      new AddrTransNdPort,
      new MemReqNdPort,
      AddrTransNdPort.default,
      Some(new AddrTransPeerPort)
    ) {
  val selectedIn         = io.in.bits
  val selectedInVirtAddr = Cat(selectedIn.memRequest.addr(wordLength - 1, 2), 0.U(2.W))
  val peer               = io.peer.get
  val resultOut          = WireDefault(0.U.asTypeOf(Valid(new MemReqNdPort)))
  val out                = resultOut.bits
  resultOutReg := resultOut
  if (isNoPrivilege) {
    io.in.ready  := io.out.ready
    io.out.valid := io.in.valid
    io.out.bits  := resultOut.bits
  }

  val exceptionBlockingReg = RegInit(false.B)
  exceptionBlockingReg := exceptionBlockingReg
  val exceptionVirtAddr = RegInit(0.U(Width.Mem.addr))
  exceptionVirtAddr      := exceptionVirtAddr
  peer.exceptionVirtAddr := exceptionVirtAddr
  when(resultOut.valid && !exceptionBlockingReg && resultOut.bits.wb.instInfo.exceptionPos =/= ExceptionPos.none) {
    exceptionBlockingReg := true.B
    exceptionVirtAddr    := selectedIn.memRequest.addr
  }

  // Fallback output
  out.wb                      := selectedIn.wb
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
        window.vseg === selectedInVirtAddr(
          selectedInVirtAddr.getWidth - 1,
          selectedInVirtAddr.getWidth - 3
        )
      target.mappedAddr := Cat(
        window.pseg,
        selectedInVirtAddr(selectedInVirtAddr.getWidth - 4, 0)
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
  when(selectedIn.cacheMaintenance.control.isInit || selectedIn.cacheMaintenance.control.isCoherentByIndex) {
    transMode := AddrTransType.directMapping
  }

  // Translate address
  val isCanTlbException = selectedIn.memRequest.isValid || selectedIn.cacheMaintenance.control.isCoherentByHit
  val translatedAddr    = WireDefault(selectedInVirtAddr)
  if (isDiffTest) {
    out.wb.instInfo.load.get.paddr := Cat(
      translatedAddr(Width.Mem._addr - 1, 2),
      selectedIn.wb.instInfo.load.get.vaddr(1, 0)
    )
    out.wb.instInfo.store.get.paddr := Cat(
      translatedAddr(Width.Mem._addr - 1, 2),
      selectedIn.wb.instInfo.store.get.vaddr(1, 0)
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
  peer.tlbTrans.virtAddr := selectedInVirtAddr
  peer.tlbTrans.isValid  := !exceptionBlockingReg
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := selectedInVirtAddr
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      if (!isNoPrivilege) {
        translatedAddr               := peer.tlbTrans.physAddr
        out.translatedMemReq.isValid := !peer.tlbTrans.exception.valid && selectedIn.memRequest.isValid
        out.cacheMaintenance.control.isL1Valid := !peer.tlbTrans.exception.valid && selectedIn.cacheMaintenance.control.isL1Valid
        out.cacheMaintenance.control.isL2Valid := !peer.tlbTrans.exception.valid && selectedIn.cacheMaintenance.control.isL2Valid

        // Handle exception
        val exceptionIndex = peer.tlbTrans.exception.bits
        when(
          selectedIn.wb.instInfo.exceptionPos === ExceptionPos.none && peer.tlbTrans.exception.valid && isCanTlbException
        ) {
          out.wb.instInfo.exceptionPos    := ExceptionPos.backend
          out.wb.instInfo.exceptionRecord := exceptionIndex
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
  if (isCacheOnPg) {
    when(peer.csr.crmd.pg === 1.U) {
      out.isCached := true.B
    }
  }
  if (isForcedCache) {
    out.isCached := true.B
  }
  if (isForcedUncached) {
    out.isCached := false.B
  }

  // Handle TLB maintenance
  peer.tlbMaintenance := selectedIn.tlbMaintenance
  when(exceptionBlockingReg || !io.in.ready || !io.in.valid) {
    peer.tlbMaintenance.isFill       := false.B
    peer.tlbMaintenance.isWrite      := false.B
    peer.tlbMaintenance.isRead       := false.B
    peer.tlbMaintenance.isInvalidate := false.B
    peer.tlbMaintenance.isSearch     := false.B
  }
  when(selectedIn.wb.instInfo.isTlb && io.in.valid && io.in.ready) {
    exceptionBlockingReg := true.B
  }
  if (isNoPrivilege) {
    peer.tlbMaintenance := DontCare
  }

  // Handle flush (actually is TLB maintenance done)
  when(io.isFlush) {
    exceptionBlockingReg := false.B
  }

  // Submit result
  when(selectedIn.wb.instInfo.isValid && !exceptionBlockingReg && io.in.ready && io.in.valid) {
    resultOut.valid := true.B
  }
}
