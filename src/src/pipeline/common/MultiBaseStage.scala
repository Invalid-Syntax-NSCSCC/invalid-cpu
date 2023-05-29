package pipeline.common

import spec._
import chisel3._
import chisel3.util._
import pipeline.common.bundles.BaseStageIo
import pipeline.common.bundles.MultiBaseStageIo
import firrtl.castRhs

abstract class MultiBaseStage[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None,
  inNum: Int = Param.issueInstInfoMaxNum,
  outNum: Int = Param.issueInstInfoMaxNum
  )
    extends Module {
  val io = IO(new MultiBaseStageIo(inNdFactory, outNdFactory, peerFactory, inNum, outNum))

  private val queueSize = 1

  private val savedIns = RegInit(Vec(inNum, blankIn))
  savedIns := savedIns
  protected val isComputeds:     Vec[Bool] = WireDefault(VecInit(Seq.fill(inNum)(true.B)))
  protected val isLastComputeds: Vec[Bool] = RegNext(isComputeds, VecInit(Seq.fill(inNum)(true.B)))
  protected val selectedIns:      Vec[InT] = WireDefault(VecInit(Seq.fill(inNum)(blankIn)))
  selectedIns.lazyZip(io.ins).lazyZip(savedIns).foreach{
    case (selectIn, in, saveIn) => {
      selectIn := Mux(
        io.isFlush,
        blankIn,
        Mux(
          in.ready,
          Mux(
            in.valid,
            in.bits,
            blankIn
          ),
          saveIn
        )
      )
    }
  }

  protected val resultOutsReg: Vec[ValidIO[OutT]] = RegInit(VecInit(Seq.fill(outNum)(0.U.asTypeOf(ValidIO(outNdFactory)))))
  resultOutsReg.foreach(_.valid := false.B)
  private val lastResultOuts = Wire(Vec(outNum, Decoupled(outNdFactory)))
  lastResultOuts.zip(resultOutsReg).foreach{
    case(lastResultOut, resultOut) =>
      lastResultOut.valid := resultOut.valid
      lastResultOut.bits := resultOut.bits
  }
  private val outQueues = lastResultOuts.map(Queue(
    _,
    entries = queueSize,
    pipe    = false,
    flow    = true,
    flush   = Some(io.isFlush)
  ))

  // Handle output
  io.outs <> outQueues

  // Handle input
  // 由于in和out不是一一对应，需要处理in.ready
  // io.ins.lazyZip(io.outs).lazyZip(isLastComputeds).lazyZip(lastResultOuts).foreach{
  //   case (in, out, isLastComputed, lastResultOut) => {
  //     in.ready := isLastComputed && ((lastResultOut.ready && !lastResultOut.valid) || out.ready )
  //   }
  // }

  io.ins.zip(savedIns).foreach{
    case(in, saveIn) => 
      when(in.valid && in.ready) {
        saveIn := in.bits
      }
  }

  // Invalidate `savedIn` when computed
  isComputeds.zip(savedIns).foreach{
    case (isComputed, saveIn) =>
      when(isComputed) {
        saveIn := blankIn
      }
  }

  // Handle flush (queue is already handled)
  when(io.isFlush) {
    io.ins.foreach(_.ready := false.B)
    io.outs.foreach(_.valid := false.B)
    isLastComputeds.foreach(_ := true.B)
    savedIns.foreach(_ := blankIn)
  }
}
