package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfReadPort
import control.enums.ExceptionPos
import pipeline.commit.WbNdPort
import pipeline.commit.bundles._
import pipeline.common.DistributedQueuePlus
import pipeline.rob.bundles._
import pipeline.rob.enums.{RegDataLocateSel, RobDistributeSel, RobInstState => State}
import spec.Param._
import spec._

// assert: commits cannot ready 1 but not 0
class Rob(
  robLength:   Int = Param.Width.Rob._length,
  pipelineNum: Int = Param.pipelineNum,
  issueNum:    Int = Param.issueInstInfoMaxNum,
  commitNum:   Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {
    // `Rob` <-> `IssueStage`
    // val emptyNum          = Output(UInt(log2Ceil(robLength + 1).W))
    val requests          = Vec(issueNum, Flipped(Decoupled(new RobReadRequestNdPort)))
    val distributeResults = Output(Vec(issueNum, new RobReadResultNdPort))

    // `Rob` <-> `Regfile`
    val regReadPortss    = Vec(issueNum, Vec(Param.regFileReadNum, Flipped(new RfReadPort)))
    val instWbBroadCasts = Output(Vec(pipelineNum, new InstWbNdPort))

    // `ExeStage / LSU` -> `Rob`
    val finishInsts = Vec(pipelineNum, Flipped(Decoupled(new WbNdPort)))

    // `Rob` -> `WbStage`
    val commits = Vec(commitNum, Decoupled(new WbNdPort))

    // `MemReqStage` <-> `Rob`
    val commitStore = Decoupled()

    // `Rob` -> `Tlb`
    val tlbMaintenanceTrigger = Output(Bool())

    // `Csr` -> `Rob`
    val hasInterrupt = Input(Bool())

    // `Cu` <-> `Rob`
    val isFlush = Input(Bool())

    val tlbDifftest = if (isDiffTest) Some(Input(new DifftestTlbFillNdPort)) else None
  })

  // fall back
  io.regReadPortss.foreach(_.foreach { port =>
    port.en   := false.B
    port.addr := DontCare
  })
  io.commits.foreach { commit =>
    commit.valid := false.B
    commit.bits  := DontCare
  }

  val matchTable = RegInit(VecInit(Seq.fill(spec.Count.reg)(RobMatchBundle.default)))

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

  io.instWbBroadCasts.zip(io.finishInsts).foreach {
    case (dst, src) =>
      dst.en    := src.valid
      dst.robId := src.bits.instInfo.robId
      dst.data  := src.bits.gprWrite.data
  }

  /** deal with finished insts
    */

  io.finishInsts.foreach { finishInst =>
    finishInst.ready := true.B
    when(finishInst.valid && finishInst.bits.instInfo.isValid) {
      queue.io.elems.lazyZip(queue.io.setPorts).zipWithIndex.foreach {
        case ((elem, set), idx) =>
          when(elem.state === State.busy && idx.U === finishInst.bits.instInfo.robId) {
            set.valid        := true.B
            set.bits.isValid := elem.isValid
            set.bits.state   := State.ready
            set.bits.wbPort  := finishInst.bits
            when(set.bits.wbPort.instInfo.exceptionPos =/= ExceptionPos.none) {
              set.bits.wbPort.instInfo.forbidParallelCommit := true.B
            }
          }
      }

      when(
        finishInst.bits.gprWrite.en &&
          finishInst.bits.instInfo.robId === matchTable(
            finishInst.bits.gprWrite.addr
          ).robId
      ) {
        matchTable(finishInst.bits.gprWrite.addr).robResData.valid := true.B
        matchTable(finishInst.bits.gprWrite.addr).robResData.bits  := finishInst.bits.gprWrite.data
      }
    }
  }

  /** Commit
    */

  val hasInterruptReg             = RegInit(false.B)
  val isDelayedMaintenanceTrigger = RegNext(false.B, false.B)

  io.tlbMaintenanceTrigger := isDelayedMaintenanceTrigger
  io.commitStore.valid     := false.B
  io.commits.zip(queue.io.dequeuePorts).zipWithIndex.foreach {
    case ((commit, deqPort), idx) =>
      when(
        deqPort.valid && deqPort.bits.state === State.ready && io.commits
          .take(idx)
          .map(_.valid)
          .foldLeft(true.B)(_ && _)
      ) {
        commit.valid := deqPort.ready
        commit.bits  := deqPort.bits.wbPort

        // commit
        if (idx == 0) {
          val isTlbMaintenanceTrigger = commit.ready &&
            deqPort.bits.wbPort.instInfo.exceptionPos === ExceptionPos.none &&
            !(io.hasInterrupt || hasInterruptReg) &&
            deqPort.bits.wbPort.instInfo.isTlb
          val isNextTlbMaintenanceTrigger = !isDelayedMaintenanceTrigger && isTlbMaintenanceTrigger
          isDelayedMaintenanceTrigger := isNextTlbMaintenanceTrigger

          if (isDiffTest) {
            commit.bits.instInfo.tlbFill.get := io.tlbDifftest.get
          }

          io.commitStore.valid := commit.ready &&
            deqPort.bits.wbPort.instInfo.exceptionPos === ExceptionPos.none &&
            !(io.hasInterrupt || hasInterruptReg) &&
            deqPort.bits.wbPort.instInfo.isStore
          deqPort.ready := commit.ready && !(io.commitStore.valid && !io.commitStore.ready) && !isNextTlbMaintenanceTrigger
        } else {
          deqPort.ready := commit.ready &&
            !io.commits(idx - 1).bits.instInfo.forbidParallelCommit &&
            !deqPort.bits.wbPort.instInfo.forbidParallelCommit &&
            queue.io.dequeuePorts(idx - 1).valid &&
            queue.io.dequeuePorts(idx - 1).ready && // promise commit in order
            !hasInterruptReg
          // &&
          // !io.hasInterrupt
        }
      }
  }

  when(io.hasInterrupt) {
    // when(io.commits(0).valid && io.commits(0).ready) {
    //   io.commits(0).bits.instInfo.exceptionRecord := Csr.ExceptionIndex.int
    //   io.commits(0).bits.instInfo.exceptionPos    := ExceptionPos.backend
    // }.otherwise {
    hasInterruptReg := true.B
    // }
  }.elsewhen(hasInterruptReg && io.commits(0).valid && io.commits(0).ready) {
    hasInterruptReg                             := false.B
    io.commits(0).bits.instInfo.exceptionRecord := Csr.ExceptionIndex.int
    io.commits(0).bits.instInfo.exceptionPos    := ExceptionPos.backend
  }

  // Distribute for issue stage

  io.requests.zip(queue.io.enqueuePorts).foreach {
    case (req, enq) =>
      req.ready := enq.ready
  }

  queue.io.enqueuePorts
    .lazyZip(io.requests)
    .lazyZip(io.distributeResults)
    .lazyZip(io.regReadPortss)
    .zipWithIndex
    .foreach {
      case ((enq, req, res, rfReadPorts), idx) =>
        // enqueue
        enq.valid                     := req.valid
        enq.bits                      := RobInstStoreBundle.default
        enq.bits.isValid              := true.B
        enq.bits.state                := State.busy
        enq.bits.wbPort.gprWrite.en   := req.bits.writeRequest.en
        enq.bits.wbPort.gprWrite.addr := req.bits.writeRequest.addr

        // distribute rob id
        res       := RobReadResultNdPort.default
        res.robId := queue.io.enqIncResults(idx)
        when(req.valid && req.ready && req.bits.writeRequest.en) {
          matchTable(req.bits.writeRequest.addr).locate           := RegDataLocateSel.rob
          matchTable(req.bits.writeRequest.addr).robId            := res.robId
          matchTable(req.bits.writeRequest.addr).robResData.valid := false.B
        }

        // request read data
        req.bits.readRequests.lazyZip(res.readResults).lazyZip(rfReadPorts).foreach {
          case (reqRead, resRead, rfReadPort) =>
            resRead := RobDistributeBundle.default

            rfReadPort.en   := true.B
            rfReadPort.addr := reqRead.addr

            val isLocateInRob      = matchTable(reqRead.addr).locate === RegDataLocateSel.rob
            val isLocateInRobValid = matchTable(reqRead.addr).robResData.valid

            val isLocateInPrevWrite        = WireDefault(false.B)
            val dataLocateInPrevWriteRobId = WireDefault(zeroWord)

            // if RAW in the same time request
            val raw = io.requests.take(idx).map(_.bits.writeRequest).map { prevWrite =>
              prevWrite.en && (prevWrite.addr === reqRead.addr)
            }
            val selectWrite = PriorityEncoderOH(raw.reverse).reverse
            when(raw.foldLeft(false.B)(_ || _)) {
              io.distributeResults.take(idx).zip(selectWrite).foreach {
                case (prevRes, prevEn) =>
                  when(prevEn) {
                    isLocateInPrevWrite        := true.B
                    dataLocateInPrevWriteRobId := prevRes.robId
                  }
              }
            }

            resRead.sel := Mux(
              reqRead.en && ((isLocateInRob && !isLocateInRobValid) || isLocateInPrevWrite),
              RobDistributeSel.robId,
              RobDistributeSel.realData
            )

            if (Param.canIssueSameWbRegInsts) {
              resRead.result := Mux(
                isLocateInPrevWrite,
                dataLocateInPrevWriteRobId,
                Mux(
                  isLocateInRob,
                  Mux(
                    isLocateInRobValid,
                    matchTable(reqRead.addr).robResData.bits,
                    matchTable(reqRead.addr).robId
                  ),
                  rfReadPort.data
                )
              )
            } else {
              resRead.result :=
                Mux(
                  isLocateInRob,
                  Mux(
                    isLocateInRobValid,
                    matchTable(reqRead.addr).robResData.bits,
                    matchTable(reqRead.addr).robId
                  ),
                  rfReadPort.data
                )
            }
        }

    }

  /** flush
    */

  when(io.isFlush) {
    // Reset registers
    matchTable.foreach(_.locate := RegDataLocateSel.regfile)
    matchTable.foreach(_.robResData.valid := false.B)
    isDelayedMaintenanceTrigger := false.B

    // Disable peer port actions
    io.commitStore.valid     := false.B
    io.tlbMaintenanceTrigger := false.B

    if (isDiffTest) {
      io.commits.map(_.bits.instInfo).foreach { info =>
        info.load.get.en  := 0.U
        info.store.get.en := 0.U
      }
    }
  }
}
