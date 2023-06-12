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

class Rob(
  robLength:   Int = Param.Width.Rob._length,
  pipelineNum: Int = Param.pipelineNum,
  issueNum:    Int = Param.issueInstInfoMaxNum,
  commitNum:   Int = Param.commitNum)
    extends Module {
  val io = IO(new Bundle {
    // `Rob` <-> `IssueStage`
    val emptyNum          = Output(UInt(Param.Width.Rob.id))
    val requests          = Input(Vec(issueNum, new RobReadRequestNdPort))
    val distributeResults = Output(Vec(issueNum, new RobReadResultNdPort))

    // `Rob` <-> `Regfile`
    val regReadPortss = Vec(issueNum, Vec(Param.regFileReadNum, Flipped(new RfReadPort)))

    // `ExeStage / LSU` -> `Rob`
    val finishInsts = Vec(pipelineNum, Flipped(Decoupled(new WbNdPort)))

    // `Rob` -> `WbStage`
    val commits = Output(Vec(commitNum, ValidIO(new WbNdPort)))

    // `Cu` -> `Rob`
    val exceptionFlush  = Input(Bool())
    val branchFlushInfo = Input(new PcSetPort)
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
    new MultiQueue(
      robLength,
      issueNum,
      commitNum,
      new RobInstStoreBundle,
      RobInstStoreBundle.default,
      needValidPorts = true
    )
  )
  queue.io.enqueuePorts.foreach { port =>
    port.valid := false.B
    port.bits  := DontCare
  }
  queue.io.setPorts.foreach { port =>
    port.valid := false.B
    port.bits  := DontCare
  }
  queue.io.dequeuePorts.foreach(_.ready := false.B)
  queue.io.isFlush := io.exceptionFlush
  io.emptyNum      := queue.io.emptyNum

  /** Distribute for issue stage
    */

  queue.io.enqueuePorts
    .lazyZip(io.requests)
    .lazyZip(io.distributeResults)
    .lazyZip(io.regReadPortss)
    .zipWithIndex
    .foreach {
      case ((enq, req, res, rfReadPorts), idx) =>
        // enqueue
        enq.valid        := req.en
        enq.bits         := RobInstStoreBundle.default
        enq.bits.isValid := true.B
        enq.bits.state   := State.busy

        // distribute rob id
        res       := RobReadResultNdPort.default
        res.robId := queue.io.enqIncResults(idx)
        when(req.writeRequest.en && req.writeRequest.addr =/= 0.U) {
          matchTable(req.writeRequest.addr).locate := RegDataLocateSel.rob
          matchTable(req.writeRequest.addr).robId  := res.robId
        }

        // request read data
        req.readRequests.lazyZip(res.readResults).lazyZip(rfReadPorts).zipWithIndex.foreach {
          case ((reqRead, resRead, rfReadPort), idx) =>
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
              }.otherwise {
                // in regfile
                rfReadPort.en   := true.B
                rfReadPort.addr := reqRead.addr
                resRead.sel     := RobDistributeSel.realData
                resRead.result  := rfReadPort.data
              }
              // if RAW in the same time request
              val raw = io.requests.take(idx).map(_.writeRequest).map { prevWrite =>
                prevWrite.en && prevWrite.addr === reqRead.addr
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

  /** deal with finished insts
    */

  io.finishInsts.foreach { finishInst =>
    finishInst.ready := true.B
    when(finishInst.valid) {
      queue.io.elemValids.get.lazyZip(queue.io.elems).lazyZip(queue.io.setPorts).zipWithIndex.foreach {
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
            matchTable(deqPort.bits.wbPort.gprWrite.addr).robId === deqPort.bits.wbPort.instInfo.robId
        ) {
          matchTable(deqPort.bits.wbPort.gprWrite.addr).locate := RegDataLocateSel.regfile
        }
      }
  }

  /** branch
    *
    * insts between branch inst and enq
    */
  when(io.branchFlushInfo.en) {

    queue.io.enqueuePorts.foreach(_.valid := false.B)
    when(io.branchFlushInfo.robId > queue.io.deq_ptr) {
      // ----- deq_ptr --*-- branch_ptr(robId) -----
      queue.io.setPorts.lazyZip(queue.io.elems).zipWithIndex.foreach {
        case ((set, elem), id) =>
          when(queue.io.deq_ptr <= id.U && id.U < io.branchFlushInfo.robId) {
            set.valid        := true.B
            set.bits.state   := elem.state
            set.bits.wbPort  := elem.wbPort
            set.bits.isValid := false.B
          }
      }
    }.otherwise {
      // --*-- branch_ptr(robId) ----- deq_ptr --*--
      queue.io.setPorts.lazyZip(queue.io.elems).zipWithIndex.foreach {
        case ((set, elem), id) =>
          when(queue.io.deq_ptr <= id.U || id.U < io.branchFlushInfo.robId) {
            set.valid        := true.B
            set.bits.state   := elem.state
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
