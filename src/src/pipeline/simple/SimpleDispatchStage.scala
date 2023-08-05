package pipeline.simple

import chisel3._
import chisel3.util._
import common.NoSavedInMultiBaseStage
import control.enums.ExceptionPos
import pipeline.simple.bundles.{RegOccupyNdPort, RegReadPort}
import spec._
import pipeline.simple.pmu.bundles.PmuDispatchInfoBundle
import pipeline.simple.ExeNdPort

class SimpleDispatchPeerPort extends Bundle {
  val regReadPorts = Vec(Param.issueInstInfoMaxNum, Vec(Param.regFileReadNum, Flipped(new RegReadPort)))
  val occupyPorts  = Output(Vec(Param.issueInstInfoMaxNum, new RegOccupyNdPort))
  val pmu          = Option.when(Param.usePmu)(Vec(Param.issueInstInfoMaxNum, new PmuDispatchInfoBundle))
}

class SimpleDispatchStage(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Module {
  val io = IO(new Bundle {
    val ins     = Vec(issueNum, Flipped(Decoupled(new FetchInstDecodeNdPort)))
    val outs    = Vec(pipelineNum, Decoupled(new ExeNdPort))
    val peer    = Some(new SimpleDispatchPeerPort)
    val isFlush = Input(Bool())
  })

  require(issueNum == pipelineNum)

  val peer = io.peer.get

  io.outs.lazyZip(io.ins).zipWithIndex.foreach {
    case ((out, in), idx) =>
      val readPorts  = peer.regReadPorts(idx)
      val occupyPort = peer.occupyPorts(idx)
      occupyPort.en    := in.valid && in.ready && in.bits.decode.info.gprWritePort.en
      occupyPort.addr  := in.bits.decode.info.gprWritePort.addr
      occupyPort.robId := in.bits.instInfo.robId

      val regReadValid = WireDefault(true.B)
      readPorts.zip(in.bits.decode.info.gprReadPorts).foreach {
        case (dst, src) =>
          dst.addr := src.addr
          when(src.en && !dst.data.valid) {
            regReadValid := false.B
          }
      }

      val noIssueForDependence = io.ins
        .take(idx)
        .map { prevIn =>
          in.bits.decode.info.gprReadPorts.map { r =>
            r.en &&
            prevIn.bits.decode.info.gprWritePort.en &&
            r.addr === prevIn.bits.decode.info.gprWritePort.addr
          }.reduce(_ || _) || !(prevIn.valid && prevIn.ready)
        }
        .foldLeft(false.B)(_ || _)

      val noIssueForNoMain = if (idx == 0) false.B else in.bits.decode.info.isIssueMainPipeline

      out.valid := in.valid && regReadValid && !noIssueForNoMain && !noIssueForDependence
      in.ready  := out.ready && regReadValid && !noIssueForNoMain && !noIssueForDependence

      out.bits.leftOperand := readPorts(0).data.bits
      out.bits.rightOperand := Mux(
        in.bits.decode.info.isHasImm,
        in.bits.decode.info.imm,
        readPorts(1).data.bits
      )

      out.bits.exeSel         := in.bits.decode.info.exeSel
      out.bits.exeOp          := in.bits.decode.info.exeOp
      out.bits.gprWritePort   := in.bits.decode.info.gprWritePort
      out.bits.jumpBranchAddr := in.bits.decode.info.jumpBranchAddr
      out.bits.instInfo       := in.bits.instInfo
  }

  val blockReg = RegInit(false.B)
  when(blockReg) {
    io.ins.foreach(_.ready := false.B)
    io.outs.foreach(_.valid := false.B)
  }
  when(
    io.ins.map { in =>
      in.ready &&
      in.valid &&
      (in.bits.instInfo.exceptionPos =/= ExceptionPos.none ||
        in.bits.instInfo.needRefetch)
    }.reduce(_ || _)
  ) {
    blockReg := true.B
  }

  if (Param.usePmu) {
    peer.pmu.get.foreach(_ <> DontCare)
  }

  when(io.isFlush) {
    blockReg := false.B
  }
}
