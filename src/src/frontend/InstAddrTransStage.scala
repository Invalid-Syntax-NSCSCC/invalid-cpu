package frontend

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import control.bundles.PipelineControlNdPort
import memory.bundles.TlbTransPort
import memory.enums.TlbMemType
import pipeline.common.BaseStage
import pipeline.mem.bundles.MemCsrNdPort
import pipeline.mem.enums.AddrTransType
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Value.Csr
import spec.Width

import scala.collection.immutable

class AddrTransNdPort extends Bundle {
  val memRequest = new Bundle {
    val isValid = Bool()
    val addr    = UInt(Width.Mem.addr)
  }
}

object AddrTransNdPort {
  def default: AddrTransNdPort = 0.U.asTypeOf(new AddrTransNdPort)
}

class MemReqNdPort extends Bundle {
  val translatedMemReq = new Bundle {
    val isValid = Bool()
    val addr    = UInt(Width.Mem.addr)
  }
}

class AddrTransPeerPort extends Bundle {
  val csr      = Input(new MemCsrNdPort)
  val tlbTrans = Flipped(new TlbTransPort)
}

class InstAddrTransStage
    extends BaseStage(
      new AddrTransNdPort,
      new MemReqNdPort,
      AddrTransNdPort.default,
      Some(new AddrTransPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  // Fallback output
  out.translatedMemReq := selectedIn.memRequest

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
  out.translatedMemReq.addr := translatedAddr
  peer.tlbTrans.memType     := TlbMemType.load

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
    }
  }

  // TODO: CSR write for TLB maintenance

  // Submit result
  resultOutReg.valid := peer.tlbTrans.exception.valid
}
