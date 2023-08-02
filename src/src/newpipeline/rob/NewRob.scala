package newpipeline.rob

import chisel3._
import chisel3.util._
import spec._
import newpipeline.rob.bundles.NewRobRequestPort
import pipeline.rob.bundles.RobQueryPcPort
import pipeline.commit.CommitNdPort
import pipeline.commit.WbNdPort
import pipeline.commit.bundles.DifftestTlbFillNdPort
import common.DistributedQueuePlus
import newpipeline.rob.bundles.NewRobMatchBundle
import pipeline.rob.bundles.RobInstStoreBundle
import utils.MultiMux1
import pipeline.rob.enums.{RegDataState, RobDistributeSel, RobInstState => State}
import control.enums.ExceptionPos
import newpipeline.rob.bundles.NewRobOccupyNdPort
import newpipeline.dispatch.NewRegReadPort

class NewRob extends Module {

  val issueNum    = Param.issueInstInfoMaxNum
  val robLength   = Param.Width.Rob._length
  val commitNum   = Param.commitNum
  val pipelineNum = Param.pipelineNum

  val io = IO(new Bundle {
    val requests = Vec(issueNum, new NewRobRequestPort)

    val occupyPorts  = Input(Vec(issueNum, new NewRobOccupyNdPort))
    val regReadPorts = Vec(issueNum, Vec(Param.regFileReadNum, Flipped(new NewRegReadPort)))

    // `ExeStage / LSU` -> `Rob`
    val finishInsts = Vec(pipelineNum + 1, Flipped(Decoupled(new WbNdPort)))

    // `Rob` <-> `Regfile`
    val regfileDatas = Input(Vec(wordLength, UInt(spec.Width.Reg.data)))

    // `Rob` -> `WbStage`
    val commits = Vec(commitNum, Decoupled(new CommitNdPort))

    // `ExePassWb_1` <-> `Rob`
    val queryPcPort = new RobQueryPcPort

    // `Cu` <-> `Rob`
    val isFlush = Input(Bool())

    val tlbDifftest = if (Param.isDiffTest) Some(Input(new DifftestTlbFillNdPort)) else None

  })

  // fall back
  io.commits.foreach { commit =>
    commit.valid := false.B
    commit.bits  := DontCare
  }

  // common match table
  val matchTable           = RegInit(VecInit(Seq.fill(spec.Count.reg)(NewRobMatchBundle.default)))
  val wbNextMatchTableData = WireDefault(Vec(Count.reg, Valid(UInt(Width.Reg.data))))
  matchTable.zip(wbNextMatchTableData).foreach {
    case (dst, src) =>
      src.valid := dst.state === RegDataState.ready
      src.bits  := dst.data
      dst.state := Mux(src.valid, RegDataState.ready, RegDataState.busy)
      dst.data  := src.bits
      dst.robId := dst.robId
  }

  io.regReadPorts.foreach { readPorts =>
    readPorts.foreach { r =>
      r.data.valid := wbNextMatchTableData(r.addr).valid
      r.data.bits  := wbNextMatchTableData(r.addr).bits
    }
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

  io.queryPcPort.pc := queue.io.elems(io.queryPcPort.robId).fetchInfo.pcAddr

  // finish insts

  io.finishInsts.foreach(_.ready := true.B)
  val finishInstFillRobBundles = Wire(Vec(pipelineNum + 1, Valid(new WbNdPort)))
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
      val mux = Module(new MultiMux1(pipelineNum, new WbNdPort, WbNdPort.default))
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

  // update match table
  matchTable.zip(wbNextMatchTableData).foreach {
    case (elem, nextElem) =>
      val mux = Module(new MultiMux1(pipelineNum, UInt(spec.Width.Reg.data), zeroWord))
      mux.io.inputs.zip(io.finishInsts).foreach {
        case (input, finishInst) =>
          input.valid := finishInst.valid &&
            finishInst.bits.instInfo.isValid &&
            finishInst.bits.gprWrite.en &&
            finishInst.bits.instInfo.robId(Param.Width.Rob._id - 1, 0) === elem.robId(Param.Width.Rob._id - 1, 0)
          input.bits := finishInst.bits.gprWrite.data
      }
      when(mux.io.output.valid && elem.state === RegDataState.busy) {
        nextElem.valid := true.B
        nextElem.bits  := mux.io.output.bits
      }
  }

  // Commit

//   val isDelayedMaintenanceTrigger = RegNext(false.B, false.B)

//   io.tlbMaintenanceTrigger := isDelayedMaintenanceTrigger
//   io.commitStore.valid     := false.B
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
          // val isTlbMaintenanceTrigger = commit.ready &&
          //   deqPort.bits.wbPort.instInfo.exceptionPos === ExceptionPos.none &&
          //   deqPort.bits.wbPort.instInfo.isTlb
          // val isNextTlbMaintenanceTrigger = !isDelayedMaintenanceTrigger && isTlbMaintenanceTrigger
          // isDelayedMaintenanceTrigger := isNextTlbMaintenanceTrigger

          if (Param.isDiffTest) {
            commit.bits.instInfo.tlbFill.get := io.tlbDifftest.get
          }

          // io.commitStore.valid := commit.ready &&
          //   deqPort.bits.wbPort.instInfo.exceptionPos === ExceptionPos.none &&
          //   deqPort.bits.wbPort.instInfo.isStore
          deqPort.ready := commit.ready // && !(io.commitStore.valid && !io.commitStore.ready) && !isNextTlbMaintenanceTrigger
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

  // occupy port for dispatch
  io.occupyPorts.foreach { occupy =>
    when(occupy.valid) {
      matchTable(occupy.addr).state := RegDataState.busy
      matchTable(occupy.addr).robId := occupy.addr
    }
  }

  // flush

  when(io.isFlush) {
    // Reset registers
    matchTable.zip(io.regfileDatas).foreach {
      case (dst, src) => {
        dst.state := RegDataState.ready
        dst.data  := src
      }
    }

    if (Param.isDiffTest) {
      io.commits.map(_.bits.instInfo).foreach { info =>
        info.load.get.en  := 0.U
        info.store.get.en := 0.U
      }
    }
  }
}
