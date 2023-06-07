package frontend.fetch

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import control.bundles.PipelineControlNdPort
import memory.bundles.TlbTransPort
import memory.enums.TlbMemType
import pipeline.common.BaseStage
import pipeline.mem.bundles.MemCsrNdPort
import pipeline.mem.enums.AddrTransType
import pipeline.mem.AddrTransPeerPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Value.Csr
import spec.Width

import scala.collection.immutable
import pipeline.mem.AddrTransPeerPort

class InstAddrTransStage extends Module {
  val io = IO(new Bundle {
    val isFlush    = Input(Bool())
    val isPcUpdate = Input(Bool())
    val pc         = Input(UInt(Width.Mem.addr))
    val out        = Flipped(Decoupled(new InstReqNdPort))
    val peer       = new AddrTransPeerPort
  })

  val peer = io.peer

  val outReg = RegInit(InstReqNdPort.default)
  outReg       := outReg
  io.out.bits  := outReg
  io.out.valid := outReg.translatedMemReq.isValid

  // Fallback output
  outReg.pc                       := io.pc
  outReg.translatedMemReq.isValid := io.isPcUpdate && io.pc.orR && !io.pc(1, 0).orR

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

  peer.tlbTrans.virtAddr := io.pc
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := io.pc
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      translatedAddr                  := peer.tlbTrans.physAddr
      outReg.translatedMemReq.isValid := io.isPcUpdate && !peer.tlbTrans.exception.valid
    }
  }

  // Can use cache
  switch(peer.csr.crmd.datm) {
    is(Csr.Crmd.Datm.suc) {
      outReg.translatedMemReq.isCached := false.B
    }
    is(Csr.Crmd.Datm.cc) {
      outReg.translatedMemReq.isCached := true.B
    }
  }

  // Handle flush
  when(io.isFlush) {
    io.out.bits.translatedMemReq.isValid := false.B
    outReg.translatedMemReq.isValid      := false.B
  }
}
