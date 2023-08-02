package newpipeline.dispatch

import chisel3.util._
import spec._
import chisel3._
import common.BaseStageWOSaveIn
import common.MultiBaseStageWOSaveIn
import pipeline.execution.ExeNdPort
import newpipeline.queue.NewFetchInstDecodeNdPort
import control.enums.ExceptionPos
import newpipeline.rob.bundles.NewRobOccupyNdPort

class NewRegReadPort extends Bundle {
  val addr = Input(UInt(Width.Reg.addr))
  val data = Output(Valid(UInt(Width.Reg.data)))
}

class NewDispatchPeerPort extends Bundle {

  val regReadPorts = Vec(Param.issueInstInfoMaxNum, Vec(Param.regFileReadNum, Flipped(new NewRegReadPort)))

  val occupyPorts = Input(Vec(Param.issueInstInfoMaxNum, new NewRobOccupyNdPort))

}

class NewDispatchStage
    extends MultiBaseStageWOSaveIn(
      new NewFetchInstDecodeNdPort,
      new ExeNdPort,
      NewFetchInstDecodeNdPort.default,
      Some(new NewDispatchPeerPort),
      inNum  = Param.issueInstInfoMaxNum,
      outNum = Param.pipelineNum
    ) {

  val issueNum    = Param.issueInstInfoMaxNum
  val pipelineNum = Param.pipelineNum
  val peer        = io.peer.get

  // fall back
  peer.regReadPorts.zip(selectedIns).foreach {
    case (readPorts, in) =>
      readPorts.zip(in.decode.info.gprReadPorts).foreach {
        case (dst, src) =>
          dst.addr := src.addr
      }
  }

  // dontcare if input not valid
  val dispatchMap = WireDefault(VecInit(Seq.fill(issueNum)(VecInit(Seq.fill(pipelineNum)(false.B)))))
  val srcEns      = WireDefault(VecInit(dispatchMap.map(_.reduceTree(_ || _))))
  val dstEns = WireDefault(dispatchMap.reduce { (a, b) =>
    VecInit(a.zip(b).map {
      case (l, r) => l || r
    })
  })

  selectedIns.lazyZip(dispatchMap).lazyZip(peer.regReadPorts).zipWithIndex.foreach {
    case ((in, dispatchSel, readPort), src_idx) =>
      val dstReady = Wire(Vec(pipelineNum, Bool()))
      val srcValid = readPort.map(_.data.valid).reduce(_ && _) &&
        !selectedIns
          .take(src_idx)
          .map { prevIn =>
            (
              prevIn.decode.info.gprWritePort.addr === in.decode.info.gprWritePort.addr &&
                in.decode.info.gprWritePort.en
            ) ||
            in.decode.info.gprReadPorts.map { r =>
              r.en && r.addr === prevIn.decode.info.gprWritePort.addr
            }.reduce(_ || _)
          }
          .reduce(_ || _)
      dstReady.lazyZip(validToOuts).lazyZip(in.decode.info.issueEn).zipWithIndex.foreach {
        case ((dispatchEn, outReady, issueEn), dst_idx) =>
          dispatchEn := outReady && issueEn && srcValid &&
            !dispatchMap
              .take(src_idx)
              .map(_(dst_idx))
              .foldLeft(false.B)(_ || _) &&
            srcEns.take(src_idx).foldLeft(true.B)(_ && _)
      }
      // select one
      dispatchSel.zip(PriorityEncoderOH(dstReady)).foreach {
        case (dst, src) =>
          dst := src && dstReady.reduce(_ || _)
      }
  }

  // -> out
  for (src_idx <- Seq.range(issueNum - 1, -1, -1)) {
    for (dst_idx <- 0 until pipelineNum) {
      when(dispatchMap(src_idx)(dst_idx) && io.ins(src_idx).valid) {
        val out = resultOuts(dst_idx)
        val in  = selectedIns(src_idx)

        io.ins(src_idx).ready   := true.B
        out.valid               := true.B
        out.bits.leftOperand    := peer.regReadPorts(src_idx)(0).data.bits
        out.bits.rightOperand   := peer.regReadPorts(src_idx)(1).data.bits
        out.bits.exeSel         := in.decode.info.exeSel
        out.bits.exeOp          := in.decode.info.exeOp
        out.bits.gprWritePort   := in.decode.info.gprWritePort
        out.bits.jumpBranchAddr := in.decode.info.jumpBranchAddr

        out.bits.instInfo := in.instInfo

      }
    }
  }

  val excpBlockReg = RegInit(false.B)
  when(excpBlockReg) {
    io.ins.foreach(_.ready := false.B)
    resultOuts.foreach(_.valid := false.B)
  }
  when(
    io.ins.map { in =>
      in.ready &&
      in.valid &&
      (in.bits.instInfo.exceptionPos =/= ExceptionPos.none ||
        !in.bits.instInfo.needRefetch)
    }.reduce(_ || _)
  ) {
    excpBlockReg := true.B
  }

  when(io.isFlush) {
    excpBlockReg := false.B
  }
}
