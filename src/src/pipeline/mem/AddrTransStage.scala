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
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Value.Csr
import spec.Width

import scala.collection.immutable

class AddrTransNdPort extends Bundle {
  val memRequest = new MemRequestNdPort
  val gprWrite   = new RfWriteNdPort
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
  out.gprWrite         := selectedIn.gprWrite
  out.instInfo         := selectedIn.instInfo
  out.translatedMemReq := selectedIn.memRequest
  out.isCached         := false.B // Fallback: Uncached

  // Select a translation mode
  val transMode = WireDefault(AddrTransType.direct) // Fallback: Direct translation
  when(!peer.csr.crmd.da && peer.csr.crmd.pg) {
    val isDirectMappingWindowHit = WireDefault(
      peer.csr.dmw.vseg ===
        selectedIn.memRequest.addr(selectedIn.memRequest.addr.getWidth - 1, selectedIn.memRequest.addr.getWidth - 3)
    )
    switch(peer.csr.crmd.plv) {
      is(Csr.Crmd.Plv.low) {
        when(
          peer.csr.dmw.plv3 && isDirectMappingWindowHit
        ) {
          transMode := AddrTransType.directMapping
        }.otherwise {
          transMode := AddrTransType.pageTableMapping
        }
      }
      is(Csr.Crmd.Plv.high) {
        when(peer.csr.dmw.plv0 && isDirectMappingWindowHit) {
          transMode := AddrTransType.directMapping
        }.otherwise {
          transMode := AddrTransType.pageTableMapping
        }
      }
    }
  }

  // Translate address
  out.instInfo.load.paddr  := out.translatedMemReq.addr
  out.instInfo.store.paddr := out.translatedMemReq.addr
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
      out.translatedMemReq.addr := selectedIn.memRequest.addr
    }
    is(AddrTransType.directMapping) {
      out.translatedMemReq.addr := Cat(
        peer.csr.dmw.pseg,
        selectedIn.memRequest.addr(selectedIn.memRequest.addr.getWidth - 4, 0)
      )
    }
    is(AddrTransType.pageTableMapping) {
      out.translatedMemReq.addr    := peer.tlbTrans.physAddr
      out.translatedMemReq.isValid := selectedIn.memRequest.isValid && !peer.tlbTrans.exception.valid

      val exceptionIndex = peer.tlbTrans.exception.bits
      out.instInfo.exceptionRecords(exceptionIndex) :=
        out.instInfo.exceptionRecords(exceptionIndex) || peer.tlbTrans.exception.valid
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

  // Submit result
  when(selectedIn.instInfo.isValid) {
    resultOutReg.valid := true.B
  }
}
