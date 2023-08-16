package pipeline.simple

import chisel3._
import chisel3.util._
import common.NoSavedInBaseStage
import common.enums.ReadWriteSel
import control.enums.ExceptionPos
import frontend.bundles.CommitFtqTrainNdPort
import memory.bundles.{TlbMaintenanceNdPort, TlbTransPort}
import memory.enums.TlbMemType
import pipeline.common.bundles.{CacheMaintenanceInstNdPort, MemCsrNdPort, MemRequestNdPort}
import pipeline.common.enums.AddrTransType
import pipeline.simple.bundles.WbNdPort
import spec.Param
import spec.Value.Csr
import spec._

import scala.collection.immutable

class AddrTransNdPort extends Bundle {
  val isAtomicStore    = Bool()
  val memRequest       = new MemRequestNdPort
  val tlbMaintenance   = new TlbMaintenanceNdPort
  val cacheMaintenance = new CacheMaintenanceInstNdPort
  val wb               = new WbNdPort
  val commitFtqPort    = new CommitFtqTrainNdPort
}

object AddrTransNdPort {
  def default = 0.U.asTypeOf(new AddrTransNdPort)
}

class AddrTransPeerPort extends Bundle {
  val csr               = Input(new MemCsrNdPort)
  val tlbTrans          = Flipped(new TlbTransPort)
  val tlbMaintenance    = Output(new TlbMaintenanceNdPort)
  val exceptionVirtAddr = Output(UInt(Width.Mem.addr))
  val commitFtqPort     = Output(new CommitFtqTrainNdPort)
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
  if (Param.isNoPrivilege) {
    io.in.ready  := io.out.ready
    io.out.valid := io.in.valid
    io.out.bits  := resultOut.bits
  }
  peer.commitFtqPort.isTrainValid    := false.B
  peer.commitFtqPort.ftqId           := DontCare
  peer.commitFtqPort.branchTakenMeta := DontCare

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
  out.isCached                := true.B // Fallback: Cached

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
      if (Param.isNoPrivilege) {
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
  if (Param.isDiffTest) {
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
      when(peer.csr.crmd.datm === 0.U) {
        out.isCached := false.B
      }
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
      val isNotCached = Mux(directMapVec(0).isHit, peer.csr.dmw(0).mat === 0.U, peer.csr.dmw(1).mat === 0.U)
      when(isNotCached) {
        out.isCached := false.B
      }
    }
    is(AddrTransType.pageTableMapping) {
      if (!Param.isNoPrivilege) {
        translatedAddr               := peer.tlbTrans.physAddr
        out.translatedMemReq.isValid := !peer.tlbTrans.exception.valid && selectedIn.memRequest.isValid
        out.cacheMaintenance.control.isL1Valid := !peer.tlbTrans.exception.valid && selectedIn.cacheMaintenance.control.isL1Valid
        out.cacheMaintenance.control.isL2Valid := !peer.tlbTrans.exception.valid && selectedIn.cacheMaintenance.control.isL2Valid
        when(peer.tlbTrans.isNotCached) {
          out.isCached := false.B
        }

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
  if (Param.isForcedCache) {
    out.isCached := true.B
  }
  if (Param.isForcedUncached) {
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
  if (Param.isNoPrivilege) {
    peer.tlbMaintenance := DontCare
  }

  // Handle flush (actually is TLB maintenance done)
  when(io.isFlush) {
    exceptionBlockingReg := false.B
  }

  // Submit result
  val commitFtqPort = RegInit(CommitFtqTrainNdPort.default)
  commitFtqPort              := DontCare
  commitFtqPort.isTrainValid := false.B
  if (Param.exeFeedBackFtqDelay) {
    // promise commit train info after exe train info
    peer.commitFtqPort := commitFtqPort
  }

  when(selectedIn.wb.instInfo.isValid && !exceptionBlockingReg && io.in.ready && io.in.valid) {
    resultOut.valid := true.B

    // In In-order issue, bpu train data commit early when no excp
    if (Param.exeFeedBackFtqDelay) {
      commitFtqPort := selectedIn.commitFtqPort
      when(out.wb.instInfo.exceptionPos =/= ExceptionPos.none) {
        commitFtqPort.isTrainValid := false.B
      }
    } else {
      peer.commitFtqPort := selectedIn.commitFtqPort
      when(out.wb.instInfo.exceptionPos =/= ExceptionPos.none) {
        peer.commitFtqPort.isTrainValid := false.B
      }
    }
  }
}
