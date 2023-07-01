package pipeline.dispatch

import chisel3._
import chisel3.util._
import spec._
import pipeline.common.MultiBaseStageWOSaveIn
import pipeline.execution.ExeNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.enums.ScoreboardState
import control.bundles.CsrReadPort

class DispatchNdPort extends Bundle {
  val issueEns  = Vec(Param.pipelineNum, Bool())
  val csrReadEn = Bool()
  val exePort   = new ExeNdPort
}

object DispatchNdPort {
  def default = 0.U.asTypeOf(new DispatchNdPort)
}

class DispatchPeerPort extends Bundle {
  // `IssueStage` <-> `Scoreboard(csr)`
  val csrOccupyPort = Output(new ScoreboardChangeNdPort)
  val csrcore       = Input(ScoreboardState())
  val csrReadPort   = Flipped(new CsrReadPort)
}

class DispatchStage(
  issueNum:       Int = Param.issueInstInfoMaxNum,
  pipelineNum:    Int = Param.pipelineNum,
  outQueueLength: Int = Param.dispatchOutQueueLength)
    extends MultiBaseStageWOSaveIn(
      new DispatchNdPort,
      new ExeNdPort,
      DispatchNdPort.default,
      Some(new DispatchPeerPort),
      issueNum,
      pipelineNum,
      outQueueLength
    ) {

  io.ins.foreach(_.ready := false.B)
  resultOutsReg.foreach { out =>
    out.valid := false.B
    out.bits  := DontCare
  }
  io.peer.get.csrOccupyPort.en   := false.B
  io.peer.get.csrOccupyPort.addr := DontCare
  io.peer.get.csrReadPort.en     := false.B
  io.peer.get.csrReadPort.addr   := false.B

  // dontcare if input valid
  val dispatchMap = WireDefault(VecInit(Seq.fill(issueNum)(VecInit(Seq.fill(pipelineNum)(false.B)))))
  val srcEns      = WireDefault(VecInit(dispatchMap.map(_.reduceTree(_ || _))))
  val dstEns = WireDefault(dispatchMap.reduce { (a, b) =>
    VecInit(a.zip(b).map {
      case (l, r) => l || r
    })
  })

  selectedIns.zip(dispatchMap).zipWithIndex.foreach {
    case ((in, dispatchSel), src_idx) =>
      val dstReady = Wire(Vec(pipelineNum, Bool()))
      dstReady.lazyZip(validToOuts).lazyZip(in.issueEns).zipWithIndex.foreach {
        case ((dispatchEn, ready, issueEn), dst_idx) =>
          dispatchEn := ready &&
            issueEn &&
            !dispatchMap
              .take(src_idx)
              .map(_(dst_idx))
              .foldLeft(false.B)(_ || _) &&
            srcEns.take(src_idx).foldLeft(true.B)(_ && _)
      }
      // select one
      dispatchSel.zip(PriorityEncoderOH(dstReady.reverse).reverse).foreach {
        case (dst, src) =>
          dst := src && dstReady.reduce(_ || _)
      }
  }

  // -> out queue
  for (src_idx <- Seq.range(issueNum - 1, -1, -1)) {
    for (dst_idx <- 0 until pipelineNum) {
      when(dispatchMap(src_idx)(dst_idx) && io.ins(src_idx).valid) {
        val out = resultOutsReg(dst_idx)
        val in  = selectedIns(src_idx)

        io.ins(src_idx).ready := true.B
        out.valid             := true.B
        out.bits              := in.exePort

        when(in.exePort.instInfo.needCsr) {
          io.peer.get.csrOccupyPort.en := true.B
        }
        if (dst_idx == Param.csrIssuePipelineIndex) {
          def csrAddr = in.exePort.jumpBranchAddr
          when(in.csrReadEn) {
            io.peer.get.csrReadPort.en   := true.B
            io.peer.get.csrReadPort.addr := csrAddr(13, 0)
            out.bits.csrData := Mux(
              csrAddr(31),
              0.U,
              io.peer.get.csrReadPort.data
            )
          }
        }
      }
    }
  }
}
