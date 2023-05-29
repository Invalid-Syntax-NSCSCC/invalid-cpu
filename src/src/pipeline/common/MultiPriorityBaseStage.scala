package pipeline.common

import spec._
import chisel3._
import chisel3.util._
import pipeline.common.bundles.MultiBaseStageIo

// 优先允许index低的input进入
abstract class MultiPriorityBaseStage[InT <: Data, OutT <: Data, PT <: Data](
  inNdFactory:  => InT,
  outNdFactory: => OutT,
  blankIn:      => InT,
  peerFactory:  => Option[PT] = None,
  inNum:        Int           = Param.issueInstInfoMaxNum,
  outNum:       Int           = Param.issueInstInfoMaxNum)
    extends MultiBaseStage(inNdFactory, outNdFactory, blankIn, peerFactory, inNum, outNum) {

  // Handle input
  val validPassThroughNum = Wire(UInt(log2Ceil(outNum).W))

  validPassThroughNum := io.outs.map(_.ready.asUInt).reduce(_ +& _)

  io.ins.lazyZip(isLastComputeds).lazyZip(lastResultOuts).zipWithIndex.foreach {
    case ((in, isLastComputed, lastResultOut), index) => {
      in.ready := isLastComputed && ((lastResultOut.ready && !lastResultOut.valid) || (index.U < validPassThroughNum))
    }
  }

}
