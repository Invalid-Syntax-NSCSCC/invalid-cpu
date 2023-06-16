package pipeline.common

import spec._
import chisel3._
import chisel3.util._
import pipeline.common.bundles.BaseStageIo
import pipeline.common.bundles.MultiBaseStageIo

abstract class MultiBaseStageWOSaveIn[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None,
  inNum:        Int           = Param.issueInstInfoMaxNum,
  outNum:       Int           = Param.pipelineNum)
    extends Module {
  val io = IO(new MultiBaseStageIo(inNdFactory, outNdFactory, peerFactory, inNum, outNum))

  private val queueSize = 1

  protected val resultOutsReg: Vec[ValidIO[OutT]] = RegInit(
    VecInit(Seq.fill(outNum)(0.U.asTypeOf(ValidIO(outNdFactory))))
  )
  resultOutsReg.foreach(_.valid := false.B)
  protected val lastResultOuts = Wire(Vec(outNum, Decoupled(outNdFactory)))
  lastResultOuts.zip(resultOutsReg).foreach {
    case (lastResultOut, resultOut) =>
      lastResultOut.valid := resultOut.valid
      lastResultOut.bits  := resultOut.bits
  }
  private val outQueues = lastResultOuts.map(
    Queue(
      _,
      entries = queueSize,
      pipe    = false,
      flow    = true,
      flush   = Some(io.isFlush)
    )
  )

  // Handle output
  io.outs <> outQueues

  protected val selectedIns: Vec[InT] = Wire(Vec(inNum, inNdFactory)) // WireDefault(VecInit(Seq.fill(inNum)(blankIn)))
  selectedIns.lazyZip(io.ins).foreach {
    case (selectIn, in) => {
      selectIn := Mux(
        io.isFlush,
        blankIn,
        Mux(
          in.valid,
          in.bits,
          blankIn
        )
      )
    }
  }

  // Handle input
  io.ins.foreach(_.ready := false.B)
  // 由于in和out不是一一对应，需要处理in.ready
  // 模板： io.ins(src).ready := validToIns(dst) && isLastComputeds(src)
  protected val validToOuts = Wire(Vec(outNum, Bool()))
  validToOuts.lazyZip(io.outs).lazyZip(lastResultOuts).foreach {
    case (v, out, lastResultOut) =>
      v := ((lastResultOut.ready && !lastResultOut.valid) || out.ready)
  }

  // Handle flush (queue is already handled)
  when(io.isFlush) {
    io.ins.foreach(_.ready := false.B)
    io.outs.foreach(_.valid := false.B)
  }
}
