package pipeline.simple

import chisel3._
import chisel3.util._
import common.BaseStage
import memory.bundles.MemResponseNdPort
import pipeline.simple.bundles.WbNdPort
import spec._

class MemResNdPort extends Bundle {
  val isAtomicStore           = Bool()
  val isAtomicStoreSuccessful = Bool()
  val isMemReq                = Bool()
  val isCached                = Bool()
  val isUnsigned              = Bool()
  val isRead                  = Bool()
  val dataMask                = UInt((Width.Mem._data / byteLength).W)
  val wb                      = new WbNdPort
}

object MemResNdPort {
  def default: MemResNdPort = 0.U.asTypeOf(new MemResNdPort)
}

class MemResPeerPort extends Bundle {
  val dCacheRes   = Input(new MemResponseNdPort)
  val uncachedRes = Input(new MemResponseNdPort)
}

class MemResStage
    extends BaseStage(
      new MemResNdPort,
      new WbNdPort,
      MemResNdPort.default,
      Some(new MemResPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  // Fallback output
  out := selectedIn.wb

  // Get read data
  val rawReadData = WireDefault(
    Mux(
      selectedIn.isCached,
      peer.dCacheRes.read.data,
      peer.uncachedRes.read.data
    )
  )
  val signedReadData   = WireDefault(0.S(Width.Reg.data))
  val unsignedReadData = WireDefault(0.U(Width.Reg.data))

  def readDataLookup[T <: Bits](modifier: UInt => T) = MuxLookup(selectedIn.dataMask, modifier(rawReadData))(
    Seq
      .range(0, 4)
      .map(index => ("b1".U << index).asUInt -> modifier(rawReadData((index + 1) * 8 - 1, index * 8))) ++
      Seq
        .range(0, 2)
        .map(index =>
          ("b11".U << index * 2).asUInt -> modifier(
            rawReadData((index + 1) * wordLength / 2 - 1, index * wordLength / 2)
          )
        ) ++
      Seq("b1111".U -> modifier(rawReadData))
  )

  signedReadData   := readDataLookup(_.asSInt)
  unsignedReadData := readDataLookup(_.asUInt)
  when(selectedIn.isRead && selectedIn.isMemReq) {
    out.gprWrite.data := Mux(selectedIn.isUnsigned, unsignedReadData, signedReadData.asUInt)
  }

  when(selectedIn.isAtomicStore) {
    out.gprWrite.data := selectedIn.isAtomicStoreSuccessful.asUInt
  }

  val isLastHasUnfinishedReq = RegNext(false.B, false.B)

  when(selectedIn.wb.instInfo.isValid) {
    // Whether memory access complete
    when(selectedIn.isMemReq) {
      when(selectedIn.isCached) {
        isComputed             := peer.dCacheRes.isComplete
        isLastHasUnfinishedReq := !peer.dCacheRes.isComplete
      }.otherwise {
        isComputed             := peer.uncachedRes.isComplete
        isLastHasUnfinishedReq := !peer.uncachedRes.isComplete
      }
    }

    // Submit result
    resultOutReg.valid := isComputed
  }

  val shouldDiscardReg = RegInit(false.B)
  shouldDiscardReg := shouldDiscardReg

  when(io.isFlush && isLastHasUnfinishedReq && !(peer.dCacheRes.isComplete || peer.uncachedRes.isComplete)) {
    shouldDiscardReg := true.B
  }

  when(shouldDiscardReg) {
    when(peer.dCacheRes.isComplete || peer.uncachedRes.isComplete) {
      shouldDiscardReg := false.B
      isComputed       := true.B
    }.otherwise {
      isComputed := false.B
    }
  }
}
