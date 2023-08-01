package common

import chisel3._
import chisel3.util._
import pipeline.common.bundles.MultiBaseStageIo
import spec._
import common.DistributedQueue

abstract class SimpleMultiBaseStage[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None,
  inNum:        Int           = Param.issueInstInfoMaxNum,
  outNum:       Int           = Param.pipelineNum)
    extends Module {
  val io = IO(new MultiBaseStageIo(inNdFactory, outNdFactory, peerFactory, inNum, outNum))

  private val queueSize = 2

  private val outQueue = Module(
    new DistributedQueue(
      outNum,
      outNum,
      outNum,
      queueSize,
      outNdFactory,
      useSyncReadMem = false
    )
  )
  outQueue.io.isFlush := io.isFlush

  // Handle output
  io.outs.zip(outQueue.io.dequeuePorts).foreach {
    case (dst, src) =>
      dst <> src
  }

  val resultOuts: Vec[DecoupledIO[OutT]] = outQueue.io.enqueuePorts // Vec(outNum, DecoupledIO(outNdFactory))

  // resultOuts.zip(outQueue.io.enqueuePorts).foreach {
  //   case (src, dst) =>
  //     dst.valid := src.valid
  //     dst.
  // }

  protected val selectedIns: Vec[InT] = Wire(Vec(inNum, inNdFactory)) // WireDefault(VecInit(Seq.fill(inNum)(blankIn)))
  selectedIns.lazyZip(io.ins).foreach {
    case (selectIn, in) => {
      selectIn := Mux(
        // io.isFlush,
        // blankIn,
        // Mux(
        in.valid,
        in.bits,
        blankIn
      )
      // )
    }
  }

  // // Handle input
  // io.ins.foreach(_.ready := false.B)
  // // 由于in和out不是一一对应，需要处理in.ready
  // // 模板： io.ins(src).ready := validToIns(dst) && isLastComputeds(src)
  // protected val validToOuts = Wire(Vec(outNum, Bool()))
  // validToOuts.lazyZip(io.outs).lazyZip(lastResultOuts).foreach {
  //   case (v, out, lastResultOut) =>
  //     v := ((lastResultOut.ready && !lastResultOut.valid) || out.ready)
  // }

  // Handle flush (queue is already handled)
  when(io.isFlush) {
    io.ins.foreach(_.ready := false.B)
    io.outs.foreach(_.valid := false.B)
  }
}
