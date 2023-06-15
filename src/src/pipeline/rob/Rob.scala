package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfWriteNdPort
import pipeline.rob.bundles.RobIdDistributePort
import pipeline.rob.bundles.RobInstStoreBundle
import pipeline.rob.enums.{RobInstState => State}
import utils._
import spec._
import utils.BiCounter
import pipeline.dataforward.bundles.ReadPortWithValid
import pipeline.writeback.WbNdPort
import pipeline.common.MultiQueue
import pipeline.rob.bundles.RobReadRequestNdPort
import pipeline.rob.bundles.RobReadResultNdPort
import pipeline.rob.bundles.RobMatchBundle
import pipeline.rob.enums.RegDataLocateSel
import common.bundles.RfReadPort
import pipeline.rob.bundles.RobDistributeBundle
import pipeline.rob.enums.RobDistributeSel
import common.bundles.PcSetPort
import control.bundles.BranchFlushInfo
import chisel3.experimental.BundleLiterals._
import chisel3.internal.firrtl.DefReg
import pipeline.rob.bundles.InstWbNdPort

class Rob(
  robLength:   Int = Param.Width.Rob._length,
  pipelineNum: Int = Param.pipelineNum,
  issueNum:    Int = Param.issueInstInfoMaxNum,
  commitNum:   Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {
    // `Rob` <-> `IssueStage`
    val emptyNum               = Output(UInt(Param.Width.Rob.id))
    val requests               = Input(Vec(issueNum, new RobReadRequestNdPort))
    val distributeResultsValid = Output(Bool())
    val distributeResults      = Output(Vec(issueNum, new RobReadResultNdPort))

    // `Rob` <-> `Regfile`
    val regReadPortss    = Vec(issueNum, Vec(Param.regFileReadNum, Flipped(new RfReadPort)))
    val instWbBroadCasts = Output(Vec(pipelineNum, new InstWbNdPort))

    // `ExeStage / LSU` -> `Rob`
    val finishInsts = Vec(pipelineNum, Flipped(Decoupled(new WbNdPort)))

    // `Rob` -> `WbStage`
    val commits = Output(Vec(commitNum, ValidIO(new WbNdPort)))

    // `Cu` <-> `Rob`
    val exceptionFlush  = Input(Bool())
    val branchFlushInfo = Input(new BranchFlushInfo)

    val robInstValids = Output(Vec(robLength, Bool()))
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
  io.distributeResultsValid := true.B

  val matchTable = RegInit(VecInit(Seq.fill(spec.Count.reg)(RobMatchBundle.default)))

  val finishInstSetQueuePorts = WireDefault(
    VecInit(
      Seq.fill(robLength)(
        ValidIO(new RobInstStoreBundle).Lit(
          _.valid -> false.B,
          _.bits -> RobInstStoreBundle.default
        )
      )
    )
  )

  val branchSetQueuePorts = WireDefault(
    VecInit(
      Seq.fill(robLength)(
        ValidIO(new RobInstStoreBundle).Lit(
          _.valid -> false.B,
          _.bits -> RobInstStoreBundle.default
        )
      )
    )
  )

  val queue = Module(
    new MultiQueue(
      robLength,
      issueNum,
      commitNum,
      new RobInstStoreBundle,
      RobInstStoreBundle.default
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
  queue.io.isFlush := io.exceptionFlush
  queue.io.setPorts.lazyZip(finishInstSetQueuePorts).lazyZip(branchSetQueuePorts).foreach {
    case (dst, src_finishInst, src_branch) =>
      dst.valid        := src_branch.valid || src_finishInst.valid
      dst.bits.isValid := Mux(src_branch.valid, src_branch.bits.isValid, src_finishInst.bits.isValid)
      dst.bits.state   := Mux(src_finishInst.valid, src_finishInst.bits.state, src_branch.bits.state)
      dst.bits.wbPort  := Mux(src_finishInst.valid, src_finishInst.bits.wbPort, src_branch.bits.wbPort)
  }
  io.emptyNum := queue.io.emptyNum
  io.robInstValids.lazyZip(queue.io.elems).lazyZip(queue.io.elemValids).lazyZip(queue.io.setPorts).foreach {
    case (dst, elem, elemValid, set) =>
      dst := elemValid && elem.isValid && (!set.valid || set.bits.isValid)
  }
  io.instWbBroadCasts.zip(io.finishInsts).foreach {
    case (dst, src) =>
      dst.en    := src.valid && io.robInstValids(src.bits.instInfo.robId)
      dst.robId := src.bits.instInfo.robId
      dst.data  := src.bits.gprWrite.data
  }

  /** deal with finished insts
    */

  io.finishInsts.foreach { finishInst =>
    finishInst.ready := true.B
    when(finishInst.valid && finishInst.bits.instInfo.isValid) {
      queue.io.elemValids.lazyZip(queue.io.elems).lazyZip(finishInstSetQueuePorts).zipWithIndex.foreach {
        case ((elemValid, elem, set), idx) =>
          when(elemValid && elem.state === State.busy && idx.U === finishInst.bits.instInfo.robId) {
            set.valid        := true.B
            set.bits.isValid := elem.isValid
            set.bits.state   := State.ready
            set.bits.wbPort  := finishInst.bits
          }
      }
    }
  }

  /** Commit
    */

  io.commits.zip(queue.io.dequeuePorts).zipWithIndex.foreach {
    case ((commit, deqPort), idx) =>
      when(
        deqPort.valid && deqPort.bits.state === State.ready && io.commits
          .take(idx)
          .map(_.valid)
          .foldLeft(true.B)(_ && _)
      ) {

        // commit
        commit.valid  := deqPort.bits.isValid
        deqPort.ready := true.B
        commit.bits   := deqPort.bits.wbPort

        // change match table
        when(
          deqPort.bits.wbPort.gprWrite.en &&
            deqPort.bits.wbPort.gprWrite.addr =/= 0.U &&
            matchTable(deqPort.bits.wbPort.gprWrite.addr).locate === RegDataLocateSel.rob &&
            matchTable(deqPort.bits.wbPort.gprWrite.addr).robId === queue.io.deqIncResults(idx)
        ) {
          matchTable(deqPort.bits.wbPort.gprWrite.addr).locate := RegDataLocateSel.regfile
        }
      }
  }

  /** Distribute for issue stage
    */
  val enqEnable = WireDefault(true.B)
  queue.io.enqueuePorts
    .lazyZip(io.requests)
    .lazyZip(io.distributeResults)
    .lazyZip(io.regReadPortss)
    .zipWithIndex
    .foreach {
      case ((enq, req, res, rfReadPorts), idx) =>
        // enqueue
        enq.valid                     := req.en && enqEnable
        enq.bits                      := RobInstStoreBundle.default
        enq.bits.isValid              := true.B
        enq.bits.state                := State.busy
        enq.bits.wbPort.gprWrite.en   := req.writeRequest.en
        enq.bits.wbPort.gprWrite.addr := req.writeRequest.addr

        // distribute rob id
        res       := RobReadResultNdPort.default
        res.robId := queue.io.enqIncResults(idx)
        when(req.writeRequest.en && req.writeRequest.addr =/= 0.U) {
          matchTable(req.writeRequest.addr).locate := RegDataLocateSel.rob
          matchTable(req.writeRequest.addr).robId  := res.robId
        }

        // request read data
        req.readRequests.lazyZip(res.readResults).lazyZip(rfReadPorts).foreach {
          case (reqRead, resRead, rfReadPort) =>
            resRead := RobDistributeBundle.default
            when(reqRead.en) {
              when(matchTable(reqRead.addr).locate === RegDataLocateSel.rob) {
                // in rob
                when(queue.io.elems(matchTable(reqRead.addr).robId).state === State.ready) {
                  resRead.sel    := RobDistributeSel.realData
                  resRead.result := queue.io.elems(matchTable(reqRead.addr).robId).wbPort.gprWrite.data
                }.otherwise {
                  // busy
                  resRead.sel    := RobDistributeSel.robId
                  resRead.result := matchTable(reqRead.addr).robId
                }

                // when inst of robId is not valid
                when(!io.robInstValids(matchTable(reqRead.addr).robId)) {
                  enqEnable                 := false.B
                  io.distributeResultsValid := false.B
                }
              }.otherwise {
                // in regfile
                rfReadPort.en   := true.B
                rfReadPort.addr := reqRead.addr
                resRead.sel     := RobDistributeSel.realData
                resRead.result  := rfReadPort.data
              }
              // if RAW in the same time request
              val raw = io.requests.take(idx).map(_.writeRequest).map { prevWrite =>
                prevWrite.en && (prevWrite.addr === reqRead.addr)
              }
              val selectWrite = PriorityEncoderOH(raw.reverse).reverse
              when(raw.foldLeft(false.B)(_ || _)) {
                io.distributeResults.take(idx).zip(selectWrite).foreach {
                  case (prevRes, prevEn) =>
                    when(prevEn) {
                      resRead.sel    := RobDistributeSel.robId
                      resRead.result := prevRes.robId
                    }
                }
              }
            }
        }

    }

  /** branch
    *
    * insts between branch inst and enq
    *
    * TODO: clean csr score board
    */
  when(io.branchFlushInfo.en) {
    queue.io.enqueuePorts.foreach(_.bits.isValid := false.B)
    when(io.branchFlushInfo.robId >= queue.io.deq_ptr) {
      // ----- deq_ptr --*(stay)*-- branch_ptr(robId) -----
      branchSetQueuePorts.lazyZip(queue.io.elems).zipWithIndex.foreach {
        case ((set, elem), id) =>
          when(id.U < queue.io.deq_ptr || io.branchFlushInfo.robId < id.U) {
            set.valid        := true.B
            set.bits.state   := State.ready // elem.state
            set.bits.wbPort  := elem.wbPort
            set.bits.isValid := false.B
          }
      }
    }.otherwise {
      // --*(stay)*-- branch_ptr(robId) ----- deq_ptr --*(stay)*--
      branchSetQueuePorts.lazyZip(queue.io.elems).zipWithIndex.foreach {
        case ((set, elem), id) =>
          when(io.branchFlushInfo.robId < id.U && id.U < queue.io.deq_ptr) {
            set.valid        := true.B
            set.bits.state   := State.ready // elem.state
            set.bits.wbPort  := elem.wbPort
            set.bits.isValid := false.B
          }
      }
    }
  }

  /** flush
    */

  when(io.exceptionFlush) {
    queue.io.enqueuePorts.foreach(_.valid := false.B)
    io.commits.foreach(_.valid := false.B)
    matchTable.foreach(_.locate := RegDataLocateSel.regfile)
  }
}
