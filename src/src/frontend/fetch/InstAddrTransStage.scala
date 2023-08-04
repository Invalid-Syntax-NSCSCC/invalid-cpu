package frontend.fetch

import chisel3._
import chisel3.util._
import common.NoSavedInBaseStage
import frontend.bundles.{FetchCsrNdPort, FtqIFNdPort}
import memory.bundles.TlbTransPort
import memory.enums.TlbMemType
import pipeline.common.enums.AddrTransType
import spec.Param.isNoPrivilege
import spec.Value.Csr
import spec.Width

class InstAddrTransPeerPort extends Bundle {
  val csr      = Input(new FetchCsrNdPort)
  val tlbTrans = Flipped(new TlbTransPort)
}
object InstAddTransPeerPort {
  def default = 0.U.asTypeOf(new InstAddrTransPeerPort)
}

class InstAddrTransStage
    extends NoSavedInBaseStage(
      new FtqIFNdPort,
      new InstReqNdPort,
      FtqIFNdPort.default,
      Some(new InstAddrTransPeerPort)
    ) {

  val selectedIn = io.in.bits

  val peer = io.peer.get
  val out  = resultOutReg.bits

  val pc = WireDefault(0.U(Width.inst))
  pc := selectedIn.ftqBlockBundle.startPc

  val hasSentException = RegInit(false.B)
  hasSentException := hasSentException

  val transMode = WireDefault(AddrTransType.direct) // Fallback: Direct translation
  val isAdef = WireDefault(
    pc(1, 0).orR ||
      (pc(31).asBool && peer.csr.crmd.plv === Csr.Crmd.Plv.low && transMode === AddrTransType.pageTableMapping)
  ) // PC is not aligned

  // Fallback output
  out.ftqBlock                  := selectedIn.ftqBlockBundle
  out.ftqId                     := selectedIn.ftqId
  out.translatedMemReq.isValid  := selectedIn.ftqBlockBundle.isValid && !isAdef && !hasSentException
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
      if (isNoPrivilege) {
        transMode := AddrTransType.directMapping
      }
    }
  }

  // Translate address
  val translatedAddr = WireDefault(pc)
  out.translatedMemReq.addr := translatedAddr
  peer.tlbTrans.memType     := TlbMemType.fetch
  peer.tlbTrans.virtAddr    := pc
  peer.tlbTrans.isValid     := !hasSentException
  switch(transMode) {
    is(AddrTransType.direct) {
      translatedAddr := pc
    }
    is(AddrTransType.directMapping) {
      translatedAddr := Mux(directMapVec(0).isHit, directMapVec(0).mappedAddr, directMapVec(1).mappedAddr)
    }
    is(AddrTransType.pageTableMapping) {
      if (!isNoPrivilege) {
        translatedAddr               := peer.tlbTrans.physAddr
        out.translatedMemReq.isValid := selectedIn.ftqBlockBundle.isValid && !peer.tlbTrans.exception.valid && !isAdef

        // Handle exception
        val isExceptionValid =
          ((isAdef || peer.tlbTrans.exception.valid) && selectedIn.ftqBlockBundle.isValid) || hasSentException
        out.exception.valid := isExceptionValid
        when(peer.tlbTrans.exception.valid && !isAdef) {
          out.exception.bits := peer.tlbTrans.exception.bits
        }
        when(isExceptionValid && io.in.ready && io.in.valid) {
          hasSentException := true.B
        }
      }
    }
  }

  // Submit result
  when(io.in.ready && io.in.valid) {
    when(selectedIn.ftqBlockBundle.isValid) {
      resultOutReg.valid := true.B
    }

    when(selectedIn.redirect) {
      resultOutReg.valid := false.B
      hasSentException   := false.B
    }
  }
  // Handle flush
  when(io.isFlush) {
    resultOutReg.valid := false.B
    hasSentException   := false.B
  }
}
