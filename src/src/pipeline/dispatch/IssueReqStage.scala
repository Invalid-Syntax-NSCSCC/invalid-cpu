package pipeline.dispatch

import chisel3._
import chisel3.util._
import control.bundles.CsrReadPort
import pipeline.common.{MultiBaseStageWOSaveIn, MultiQueue}
import pipeline.dispatch.bundles.{ReservationStationBundle, ScoreboardChangeNdPort}
import pipeline.dispatch.enums.ScoreboardState
import pipeline.execution.ExeNdPort
import pipeline.rob.bundles.{InstWbNdPort, RobReadRequestNdPort, RobReadResultNdPort}
import pipeline.rob.enums.RobDistributeSel
import spec.Param.{csrIssuePipelineIndex, loadStoreIssuePipelineIndex}
import spec._
import control.enums.ExceptionPos
import pipeline.common.SimpleMultiBaseStage

class IssueReqStagePeerPort(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Bundle {

  // `IssueStage` <-> `Rob`
  val robEmptyNum = Input(UInt(log2Ceil(Param.Width.Rob._length + 1).W))
  val requests    = Output(Vec(issueNum, new RobReadRequestNdPort))

  // `Cu` -> `IssueStage`
  val branchFlush = Input(Bool())

  val plv = Input(UInt(2.W))
}

class IssueReqStage(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends SimpleMultiBaseStage(
      new FetchInstDecodeNdPort,
      new DispatchNdPort,
      FetchInstDecodeNdPort.default,
      Some(new IssueReqStagePeerPort),
      issueNum,
      1
    ) {

  val peer = io.peer.get
  val out  = resultOuts(0)

  // Fallback
  peer.requests.foreach { req =>
    req.en := false.B
    req.readRequests.foreach { port =>
      port.en   := false.B
      port.addr := DontCare
    }
    req.writeRequest.en   := false.B
    req.writeRequest.addr := DontCare
  }
  out.valid := false.B
  out.bits.dispatchs.foreach { dp =>
    dp.valid         := false.B
    dp.resultPortIdx := DontCare
    dp.regReadPort   := DontCare
  }

  // stop fetch when branch
  val fetchEnableFlag = RegInit(true.B)

  val dispatchMap = WireDefault(
    VecInit(
      Seq.fill(issueNum)(VecInit(Seq.fill(pipelineNum)(false.B)))
    )
  )

  dispatchMap.lazyZip(io.ins).zipWithIndex.foreach {
    case ((dispatchSel, in), src_idx) =>
      when(out.ready && in.valid && in.bits.instInfo.isValid && src_idx.U < peer.robEmptyNum) {
        val validSels = Wire(Vec(pipelineNum, Bool()))
        validSels.zip(in.bits.decode.info.issueEn).zipWithIndex.foreach {
          case ((validSel, en), dst_idx) =>
            validSel := en && !dispatchMap.take(src_idx).map(_(dst_idx)).foldLeft(false.B)(_ || _)
        }

        // select one
        // 优先发射到大的
        dispatchSel.zip(PriorityEncoderOH(validSels.reverse).reverse).foreach {
          case (dst, src) =>
            dst := src && validSels.reduce(_ || _)
        }
      }
  }

  val srcEns = dispatchMap.map(_.reduceTree(_ || _))
  val dstEns = dispatchMap.reduce { (a, b) =>
    VecInit(a.zip(b).map { case (fr, to) => fr || to })
  }

  out.valid := srcEns.reduce(_ || _)

  io.ins.zip(srcEns).foreach {
    case (in, en) =>
      in.ready := en
  }

  // bits

  out.bits.dispatchs.zip(dstEns).foreach {
    case (dp, en) =>
      dp.valid := en
  }

  val selectPipelineIndices = dispatchMap.map(PriorityEncoder(_))
  selectPipelineIndices.zipWithIndex.reverse.foreach {
    case (pipelineIndex, src_idx) =>
      out.bits.dispatchs(pipelineIndex).resultPortIdx := src_idx.U

      val rp  = out.bits.dispatchs(pipelineIndex).regReadPort
      val src = io.ins(src_idx).bits

      rp.preExeInstInfo := src.decode.info
      rp.instInfo       := src.instInfo
      when(peer.plv =/= 0.U && src.decode.info.isPrivilege) {
        when(src.instInfo.exceptionPos =/= ExceptionPos.none) {
          rp.instInfo.exceptionPos    := ExceptionPos.backend
          rp.instInfo.exceptionRecord := Csr.ExceptionIndex.ipe
        }
      }
  }

  // request
  io.ins.zip(peer.requests).foreach {
    case (in, req) =>
      req.en           := in.ready && in.valid
      req.writeRequest := in.bits.decode.info.gprWritePort
      req.readRequests.zip(in.bits.decode.info.gprReadPorts).foreach {
        case (dst, src) =>
          dst := src
      }
  }

  when(io.peer.get.branchFlush) {
    io.peer.get.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
    fetchEnableFlag := false.B
  }

  when(io.isFlush) {
    io.peer.get.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
    fetchEnableFlag := true.B
  }

}
