package pipeline.simple

import chisel3._
import chisel3.util._
import pipeline.simple.bundles.WbNdPort
import common.DistributedQueuePlus
import control.enums.ExceptionPos
import pipeline.common.bundles.{DifftestTlbFillNdPort, RobQueryPcPort}
import pipeline.simple.bundles.RobInstStoreBundle

import pipeline.common.enums.{RegDataState, RobDistributeSel, RobInstState => State}
import pipeline.simple.bundles.{RegReadPort, RobRequestPort}
import pipeline.simple.bundles.RegMatchBundle
import spec._
import utils.MultiMux1

class Rob(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  robLength:   Int = Param.Width.Rob._length,
  commitNum:   Int = Param.commitNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Module {

  val wbNum = if (Param.isMainResWbEarly) pipelineNum + 1 else pipelineNum

  val io = IO(new Bundle {
    val requests = Vec(issueNum, new RobRequestPort)

    // `ExeStage / LSU` -> `Rob`
    val finishInsts = Vec(wbNum, Flipped(Decoupled(new WbNdPort)))

    // `Rob` -> `WbStage`
    val commits = Vec(commitNum, Decoupled(new CommitNdPort))

    // `Cu` <-> `Rob`
    val isFlush = Input(Bool())

    // `Rob` -> `TLB`
    val tlbMaintenanceTrigger = Output(Bool())

    val tlbDifftest = if (Param.isDiffTest) Some(Input(new DifftestTlbFillNdPort)) else None

  })

  // fall back
  io.commits.foreach { commit =>
    commit.valid := false.B
    commit.bits  := DontCare
  }

  val queue = Module(
    new DistributedQueuePlus(
      issueNum,
      commitNum,
      Param.Width.Rob._channelNum,
      Param.Width.Rob._channelLength,
      new RobInstStoreBundle,
      RobInstStoreBundle.default,
      useSyncReadMem = false
    )
  )

  queue.io.enqueuePorts.foreach { port =>
    port.valid := false.B
    port.bits  := DontCare
  }
  queue.io.setPorts.zip(queue.io.elems).foreach {
    case (dst, src) =>
      dst.valid := false.B
      dst.bits  := src
  }
  queue.io.dequeuePorts.foreach(_.ready := false.B)
  queue.io.isFlush := io.isFlush

  // finish insts

  io.finishInsts.foreach(_.ready := true.B)
  val finishInstFillRobBundles = Wire(Vec(wbNum, Valid(new WbNdPort)))
  finishInstFillRobBundles.zip(io.finishInsts).foreach {
    case (dst, src) =>
      dst.valid := src.valid
      dst.bits  := src.bits
      when(src.bits.instInfo.exceptionPos =/= ExceptionPos.none) {
        dst.bits.instInfo.forbidParallelCommit := true.B
      }
  }

  // set rob queue
  queue.io.elems.zip(queue.io.setPorts).zipWithIndex.foreach {
    case ((elem, set), idx) =>
      val mux = Module(new MultiMux1(wbNum, new WbNdPort, WbNdPort.default))
      mux.io.inputs.zip(finishInstFillRobBundles).foreach {
        case (input, finishInst) =>
          input.valid := idx.U === finishInst.bits.instInfo.robId &&
            finishInst.valid &&
            finishInst.bits.instInfo.isValid
          input.bits := finishInst.bits
      }
      set.valid       := elem.state === State.busy && mux.io.output.valid
      set.bits.state  := State.ready
      set.bits.wbPort := mux.io.output.bits
  }

  // Commit

  val isDelayedMaintenanceTrigger = RegNext(false.B, false.B)

  io.tlbMaintenanceTrigger := isDelayedMaintenanceTrigger
  io.commits.zip(queue.io.dequeuePorts).zipWithIndex.foreach {
    case ((commit, deqPort), idx) =>
      commit.bits.fetchInfo := deqPort.bits.fetchInfo
      commit.bits.instInfo  := deqPort.bits.wbPort.instInfo
      commit.bits.gprWrite  := deqPort.bits.wbPort.gprWrite

      when(
        deqPort.valid && deqPort.bits.state === State.ready && io.commits
          .take(idx)
          .map(_.valid)
          .foldLeft(true.B)(_ && _)
      ) {
        commit.valid := deqPort.ready

        // commit
        if (idx == 0) {
          val isTlbMaintenanceTrigger = commit.ready &&
            deqPort.bits.wbPort.instInfo.exceptionPos === ExceptionPos.none &&
            deqPort.bits.wbPort.instInfo.isTlb
          val isNextTlbMaintenanceTrigger = !isDelayedMaintenanceTrigger && isTlbMaintenanceTrigger
          isDelayedMaintenanceTrigger := isNextTlbMaintenanceTrigger

          if (Param.isDiffTest) {
            commit.bits.instInfo.tlbFill.get := io.tlbDifftest.get
          }

          deqPort.ready := commit.ready && !isNextTlbMaintenanceTrigger
        } else {
          deqPort.ready := commit.ready &&
            !io.commits(idx - 1).bits.instInfo.forbidParallelCommit &&
            !deqPort.bits.wbPort.instInfo.forbidParallelCommit &&
            queue.io.dequeuePorts(idx - 1).valid &&
            queue.io.dequeuePorts(idx - 1).ready
        }
      }
  }

  // Distribute rob id for decode
  io.requests.zip(queue.io.enqueuePorts).zipWithIndex.foreach {
    case ((req, enq), instIdx) =>
      req.result.valid := enq.ready
      req.result.bits  := queue.io.enqIncResults(instIdx)

      enq.valid          := req.request.valid
      enq.bits.state     := State.busy
      enq.bits.fetchInfo := req.request.bits
  }

  // flush

  when(io.isFlush) {

    if (Param.isDiffTest) {
      io.commits.map(_.bits.instInfo).foreach { info =>
        info.load.get.en  := 0.U
        info.store.get.en := 0.U
      }
    }
  }
}
