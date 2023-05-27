package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import common.enums.ReadWriteSel
import control.bundles.PipelineControlNdPort
import memory.bundles.MemRequestHandshakePort
import pipeline.common.BaseStage
import pipeline.mem.bundles.MemRequestNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec._

class MemReqNdPort extends Bundle {
  val translatedMemReq = new MemRequestNdPort
  val isCached         = Bool()
  val gprWrite         = new RfWriteNdPort
  val instInfo         = new InstInfoNdPort
}

object MemReqNdPort {
  def default: MemReqNdPort = 0.U.asTypeOf(new MemReqNdPort)
}

class MemReqPeerPort extends Bundle {
  val dCacheReq   = new MemRequestHandshakePort
  val uncachedReq = new MemRequestHandshakePort
}

class MemReqStage
    extends BaseStage(
      new MemReqNdPort,
      new MemResNdPort,
      MemReqNdPort.default,
      Some(new MemReqPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  // Fallback output
  out.gprWrite   := selectedIn.gprWrite
  out.instInfo   := selectedIn.instInfo
  out.isHasReq   := selectedIn.translatedMemReq.isValid
  out.isUnsigned := selectedIn.translatedMemReq.read.isUnsigned
  out.isCached   := selectedIn.isCached
  out.isRead     := selectedIn.translatedMemReq.rw === ReadWriteSel.read
  out.dataMask   := selectedIn.translatedMemReq.mask

  // Fallback peer
  peer.dCacheReq.client           := selectedIn.translatedMemReq
  peer.uncachedReq.client         := selectedIn.translatedMemReq
  peer.dCacheReq.client.isValid   := selectedIn.translatedMemReq.isValid && selectedIn.isCached
  peer.uncachedReq.client.isValid := selectedIn.translatedMemReq.isValid && !selectedIn.isCached

  when(selectedIn.instInfo.isValid) {
    // Whether memory request is submitted
    when(selectedIn.isCached) {
      isComputed := peer.dCacheReq.isReady
    }.otherwise {
      isComputed := peer.uncachedReq.isReady
    }
    // Submit result
    resultOutReg.valid := isComputed
  }
}
