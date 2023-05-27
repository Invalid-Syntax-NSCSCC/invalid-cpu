package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import control.bundles.PipelineControlNdPort
import memory.bundles.MemResponseNdPort
import pipeline.common.BaseStage
import pipeline.writeback.WbNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec._

import scala.collection.immutable

class MemResNdPort extends Bundle {
  val isHasReq   = Bool()
  val isCached   = Bool()
  val isUnsigned = Bool()
  val isRead     = Bool()
  val dataMask   = UInt((Width.Mem._data / byteLength).W)
  val gprWrite   = new RfWriteNdPort
  val instInfo   = new InstInfoNdPort
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
  out.gprWrite := selectedIn.gprWrite
  out.instInfo := selectedIn.instInfo

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
  when(selectedIn.isRead) {
    out.gprWrite.data := Mux(selectedIn.isUnsigned, unsignedReadData, signedReadData.asUInt)
  }

  when(selectedIn.instInfo.isValid) {
    // Whether memory access complete
    when(selectedIn.isCached) {
      isComputed := peer.dCacheRes.isComplete
    }.otherwise {
      isComputed := peer.uncachedRes.isComplete
    }
    // Submit result
    resultOutReg.valid := isComputed
  }
}
