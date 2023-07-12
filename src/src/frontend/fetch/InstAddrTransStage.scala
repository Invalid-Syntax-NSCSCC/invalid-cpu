package frontend.fetch

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import control.enums.ExceptionPos
import memory.enums.TlbMemType
import memory.bundles.{TlbMaintenanceNdPort, TlbTransPort}
import pipeline.memory.enums.AddrTransType
import frontend.bundles.{FetchCsrNdPort, FtqBlockBundle, FtqIFNdPort}
import pipeline.commit.bundles.{DifftestTlbFillNdPort, InstInfoNdPort}
import pipeline.common.BaseStage
import pipeline.memory.MemReqNdPort
import pipeline.memory.bundles.{CacheMaintenanceInstNdPort, MemCsrNdPort, MemRequestNdPort}
import spec.Param.isDiffTest
import spec.Value.Csr
import spec.{Param, Width}

import scala.collection.immutable

class InstAddrTransPeerPort extends Bundle {
  val csr      = Input(new FetchCsrNdPort)
  val tlbTrans = Flipped(new TlbTransPort)
}
object InstAddTransPeerPort {
  def default = 0.U.asTypeOf(new InstAddrTransPeerPort)
}

class InstAddrTransStage
    extends BaseStage(
      new FtqIFNdPort,
      new InstReqNdPort,
      FtqIFNdPort.default,
      Some(new InstAddrTransPeerPort)
    ) {

  val peer = io.peer.get
  val out  = resultOutReg.bits

  val pc = WireDefault(0.U(Width.inst))
  pc := selectedIn.ftqBlockBundle.startPc
  // val isAdef           = WireDefault(pc(1, 0).orR) // PC is not aligned
  val hasSentException = RegInit(false.B)
  hasSentException := hasSentException

  val isBlockPcNext = hasSentException

  val transMode = WireDefault(AddrTransType.direct) // Fallback: Direct translation
  val isAdef = WireDefault(
    pc(1, 0).orR ||
      (pc(31).asBool && peer.csr.crmd.plv === Csr.Crmd.Plv.low && transMode === AddrTransType.pageTableMapping)
  ) // PC is not aligned

  // Fallback output
  out.ftqBlock                  := selectedIn.ftqBlockBundle
  out.ftqId                     := selectedIn.ftqId
  out.translatedMemReq.isValid  := (selectedIn.ftqBlockBundle.isValid) && !isAdef
  out.translatedMemReq.isCached := true.B // Fallback: isCached
  out.exception.valid           := isAdef
  out.exception.bits            := spec.Csr.ExceptionIndex.adef

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
      // if (isNoPrivilege) {
      //   transMode := AddrTransType.directMapping
      // }
    }
  }

  // Handle exception
  def handleException(): Unit = {
    val isExceptionValid = isAdef || peer.tlbTrans.exception.valid
    out.exception.valid := isExceptionValid
    when(peer.tlbTrans.exception.valid && !isAdef) {
      out.exception.bits := peer.tlbTrans.exception.bits
    }
    when(isExceptionValid) {
      hasSentException := true.B
    }
  }

  // Translate address
  val translatedAddr = WireDefault(pc)
  out.translatedMemReq.addr := translatedAddr
  peer.tlbTrans.memType     := TlbMemType.fetch
  peer.tlbTrans.virtAddr    := pc
  peer.tlbTrans.isValid     := false.B
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := pc
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      peer.tlbTrans.isValid := selectedIn.ftqBlockBundle.isValid
      translatedAddr        := peer.tlbTrans.physAddr
      out.translatedMemReq.isValid := (selectedIn.ftqBlockBundle.isValid) &&
        !peer.tlbTrans.exception.valid && !isAdef

      handleException()
    }
  }
  io.in.ready := inReady && !isBlockPcNext

  // Submit result
  when(selectedIn.ftqBlockBundle.isValid) {
    resultOutReg.valid := true.B
  }

  // Handle flush
  when(io.isFlush) {
    hasSentException := false.B
  }

}
