package pipeline.dispatch

import chisel3._
import chisel3.util._
import spec._
import pipeline.common.MultiBaseStageWOSaveIn
import pipeline.execution.ExeNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.enums.ScoreboardState
import pipeline.rob.bundles.InstWbNdPort
import pipeline.dispatch.bundles.ReservationStationBundle
import control.enums.ExceptionPos
import pipeline.dispatch.rs._
import pmu.bundles.PmuDispatchBundle

// class DispatchNdPort extends Bundle {
//   val issueEns = Vec(Param.pipelineNum, Bool())
//   val exePort  = new ExeNdPort
// }

// object DispatchNdPort {
//   def default = 0.U.asTypeOf(new DispatchNdPort)
// }

class NewDispatchPeerPort extends Bundle {

  val plv = Input(UInt(2.W))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(Param.pipelineNum, new InstWbNdPort))

  // pmu
  val pmu_dispatchInfos = if (Param.usePmu) Some(Output(Vec(Param.pipelineNum, new PmuDispatchBundle))) else None
}

class NewDispatchStage(
  issueNum:       Int = Param.issueInstInfoMaxNum,
  pipelineNum:    Int = Param.pipelineNum,
  outQueueLength: Int = Param.dispatchOutQueueLength)
    extends MultiBaseStageWOSaveIn(
      new ReservationStationBundle,
      new ExeNdPort,
      ReservationStationBundle.default,
      Some(new NewDispatchPeerPort),
      issueNum,
      pipelineNum,
      outQueueLength,
      passOut = Param.isWakeUpPassThroughExe
    ) {

  val peer = io.peer.get

  io.ins.foreach(_.ready := false.B)
  resultOuts.foreach { out =>
    out.valid := false.B
    out.bits  := DontCare
  }

  val reservationStations = Seq.range(0, pipelineNum).map { idx =>
    Module(
      if (Param.isOutOfOrderIssue && idx != Param.loadStoreIssuePipelineIndex)
        // new SimpleOoOReservationStation(
        //   Param.Width.Rob._channelLength,
        //   true
        // )
        new OoOReservationStation(
          Param.Width.Rob._channelLength,
          1,
          1,
          1,
          Param.Width.Rob._channelLength,
          true
        )
      else
        new InOrderReservationStation(
          Param.Width.Rob._channelLength,
          1,
          1,
          1,
          Param.Width.Rob._channelLength,
          true
        )
    )
  }

  reservationStations.foreach { rs =>
    rs.io.writebacks.zip(io.peer.get.writebacks).foreach {
      case (dst, src) =>
        dst := src
    }
    rs.io.isFlush := io.isFlush
    rs.io.enqueuePorts.foreach { enq =>
      enq.valid := false.B
      enq.bits  := DontCare
    }
  }

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
      dstReady.lazyZip(reservationStations).lazyZip(in.regReadPort.preExeInstInfo.issueEn).zipWithIndex.foreach {
        case ((dispatchEn, rs, issueEn), dst_idx) =>
          dispatchEn := rs.io.enqueuePorts(0).ready &&
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

  // -> reservation stations
  for (src_idx <- Seq.range(issueNum - 1, -1, -1)) {
    for (dst_idx <- 0 until pipelineNum) {
      when(dispatchMap(src_idx)(dst_idx) && io.ins(src_idx).valid) {
        val out = reservationStations(dst_idx).io.enqueuePorts(0)
        val in  = selectedIns(src_idx)

        io.ins(src_idx).ready := true.B
        out.valid             := true.B
        out.bits              := in
      }
    }
  }

  // -> out queue
  val deqEns = Wire(Vec(pipelineNum, Bool()))
  reservationStations.lazyZip(validToOuts).lazyZip(resultOuts).zipWithIndex.foreach {
    case ((rs, outReady, out), idx) =>
      val rsDeqPort = rs.io.dequeuePorts(0)
      val deqEn = WireDefault(
        outReady &&
          rsDeqPort.valid
      )
      deqEns(idx)     := deqEn
      out.valid       := deqEn
      rsDeqPort.ready := deqEn

      out.bits.leftOperand  := rsDeqPort.bits.robResult.readResults(0).result
      out.bits.rightOperand := rsDeqPort.bits.robResult.readResults(1).result
      out.bits.exeSel       := rsDeqPort.bits.regReadPort.preExeInstInfo.exeSel
      out.bits.exeOp        := rsDeqPort.bits.regReadPort.preExeInstInfo.exeOp

      out.bits.gprWritePort := rsDeqPort.bits.regReadPort.preExeInstInfo.gprWritePort
      // jumbBranch / memLoadStort / csr
      out.bits.jumpBranchAddr := rsDeqPort.bits.regReadPort.preExeInstInfo.jumpBranchAddr

      out.bits.instInfo       := rsDeqPort.bits.regReadPort.instInfo
      out.bits.instInfo.robId := rsDeqPort.bits.robResult.robId

      when(
        peer.plv === 3.U &&
          rsDeqPort.bits.regReadPort.preExeInstInfo.isPrivilege &&
          rsDeqPort.bits.regReadPort.instInfo.exceptionPos === ExceptionPos.none
      ) {
        out.bits.instInfo.exceptionPos    := ExceptionPos.backend
        out.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.ipe
      }
  }

  if (Param.usePmu) {
    io.peer.get.pmu_dispatchInfos.get.zip(reservationStations).foreach {
      case (dst, rs) =>
        dst := rs.io.pmu_dispatchInfo.get
    }
  }

  when(io.isFlush) {
    resultOuts.foreach { out =>
      out.valid := false.B
    }
  }

}
