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
import pipeline.dispatch.rs.InOrderReservationStation
import control.enums.ExceptionPos

// class DispatchNdPort extends Bundle {
//   val issueEns = Vec(Param.pipelineNum, Bool())
//   val exePort  = new ExeNdPort
// }

// object DispatchNdPort {
//   def default = 0.U.asTypeOf(new DispatchNdPort)
// }

class NewDispatchPeerPort extends Bundle {
  // `IssueStage` <-> `Scoreboard(csr)`
  val csrOccupyPort = Output(new ScoreboardChangeNdPort)
  val csrScore      = Input(ScoreboardState())

  val plv = Input(UInt(2.W))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(Param.pipelineNum, new InstWbNdPort))
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
      outQueueLength
    ) {

  val peer = io.peer.get

  io.ins.foreach(_.ready := false.B)
  resultOutsReg.foreach { out =>
    out.valid := false.B
    out.bits  := DontCare
  }
  io.peer.get.csrOccupyPort.en   := false.B
  io.peer.get.csrOccupyPort.addr := DontCare

  val reservationStations = Seq.fill(pipelineNum)(
    Module(
      new InOrderReservationStation(
        4, 1, 1, 1, 4, true
      )
    )
  )

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
  reservationStations.lazyZip(validToOuts).lazyZip(resultOutsReg).zipWithIndex.foreach {
    case ((rs, outReady, out), idx) =>
      val rsDeqPort = rs.io.dequeuePorts(0)
      val deqEn = WireDefault(
        outReady &&
          rsDeqPort.valid &&
          !(
            rsDeqPort.bits.regReadPort.preExeInstInfo.needCsr &&
              peer.csrScore =/= ScoreboardState.free
          )
      )
      if (idx == Param.csrIssuePipelineIndex) {
        when(
          deqEns(Param.loadStoreIssuePipelineIndex) &&
            reservationStations(Param.loadStoreIssuePipelineIndex).io
              .dequeuePorts(0)
              .bits
              .regReadPort
              .preExeInstInfo
              .needCsr
        ) {
          deqEn := false.B
        }
      }
      deqEns(idx)     := deqEn
      out.valid       := deqEn
      rsDeqPort.ready := deqEn

      when(deqEn && rsDeqPort.bits.regReadPort.preExeInstInfo.needCsr) {
        peer.csrOccupyPort.en := true.B
      }

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
        peer.plv =/= 0.U &&
          rsDeqPort.bits.regReadPort.preExeInstInfo.isPrivilege &&
          rsDeqPort.bits.regReadPort.instInfo.exceptionPos === ExceptionPos.none
      ) {
        out.bits.instInfo.exceptionPos    := ExceptionPos.backend
        out.bits.instInfo.exceptionRecord := Csr.ExceptionIndex.ipe
      }
  }

  // // out
  // reservationStation.io.dequeuePorts.lazyZip(resultOuts).zipWithIndex.foreach {
  //   case ((rs, out), idx) =>
  //     val deqEn = out.ready && rs.valid && deqEns.take(idx).foldLeft(true.B)(_ && _)
  //     deqEns(idx) := deqEn
  //     out.valid   := deqEn
  //     rs.ready    := deqEn

  // out.bits.exePort.leftOperand  := rs.bits.robResult.readResults(0).result
  // out.bits.exePort.rightOperand := rs.bits.robResult.readResults(1).result
  // out.bits.exePort.exeSel       := rs.bits.regReadPort.preExeInstInfo.exeSel
  // out.bits.exePort.exeOp        := rs.bits.regReadPort.preExeInstInfo.exeOp

  // out.bits.exePort.gprWritePort := rs.bits.regReadPort.preExeInstInfo.gprWritePort
  // // jumbBranch / memLoadStort / csr
  // out.bits.exePort.jumpBranchAddr := rs.bits.regReadPort.preExeInstInfo.jumpBranchAddr

  // // Read Csr Data in exe

  // out.bits.exePort.instInfo       := rs.bits.regReadPort.instInfo
  // out.bits.exePort.instInfo.robId := rs.bits.robResult.robId

  // when(
  //   peer.plv =/= 0.U &&
  //     rs.bits.regReadPort.preExeInstInfo.isPrivilege &&
  //     rs.bits.regReadPort.instInfo.exceptionPos === ExceptionPos.none
  // ) {
  //   out.bits.exePort.instInfo.exceptionPos    := ExceptionPos.backend
  //   out.bits.exePort.instInfo.exceptionRecord := Csr.ExceptionIndex.ipe
  // }

  // out.bits.issueEns.zip(rs.bits.regReadPort.preExeInstInfo.issueEn).foreach {
  //   case (dst, src) =>
  //     dst := src
  // }

  // }
}
