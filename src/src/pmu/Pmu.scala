package pmu

import spec._
import chisel3._
import chisel3.util._
import pmu.bundles.PmuBranchPredictNdPort
import pmu.bundles.PmuDispatchBundle
import pmu.bundles.PmuBranchMisPredictExeNdPort

class Pmu extends Module {
  val io = IO(new Bundle {
    val instqueueFull      = Input(Bool())
    val instqueueFullValid = Input(Bool())
    val instQueueEmpty     = Input(Bool())
    val branchInfo         = Input(new PmuBranchPredictNdPort)
    val dispatchInfos      = Input(Vec(Param.pipelineNum, new PmuDispatchBundle))
    val robFull            = Input(Bool())
  })

  def r: UInt = {
    dontTouch(RegInit(0.U(64.W)))
  }

  def inc(reg: UInt): Unit = {
    reg := reg + 1.U
  }

  def condInc(reg: UInt, cond: Bool): Unit = {
    when(cond) {
      reg := reg + 1.U
    }
  }

  val timer                = r
  val instQueueIsFull      = r
  val instQueueIsFullValid = r // 当idle或redirect阻塞取指的时候不计入
  val instQueueEmpty       = r

  inc(timer)
  when(io.instqueueFull) {
    inc(instQueueIsFull)
    when(io.instqueueFullValid) {
      inc(instQueueIsFullValid)
    }
  }
  when(io.instQueueEmpty) {
    inc(instQueueEmpty)
  }

  val dispatchBubbleFromBackends        = Seq.fill(Param.pipelineNum)(r)
  val dispatchBubbleFromDataDependences = Seq.fill(Param.pipelineNum)(r)
  val dispatchBubbleFromRSEmptys        = Seq.fill(Param.pipelineNum)(r)
  val dispatchRSFulls                   = Seq.fill(Param.pipelineNum)(r)
  val dispatchRSEnqueueNum              = Seq.fill(Param.pipelineNum)(r)

  io.dispatchInfos.zipWithIndex.foreach {
    case (dispatchInfo, idx) =>
      when(dispatchInfo.isFull) {
        inc(dispatchRSFulls(idx))
      }
      when(dispatchInfo.bubbleFromBackend) {
        inc(dispatchBubbleFromBackends(idx))
      }
      when(dispatchInfo.bubbleFromDataDependence) {
        inc(dispatchBubbleFromDataDependences(idx))
      }
      when(dispatchInfo.bubbleFromRSEmpty) {
        inc(dispatchBubbleFromRSEmptys(idx))
      }
      condInc(dispatchRSEnqueueNum(idx), dispatchInfo.enqueue)
  }

  val robFull = r
  when(io.robFull) {
    inc(robFull)
  }

  val branch                    = r
  val branchSuccess             = r
  val branchFail                = r
  val branchUnconditional       = r
  val branchUnconditionalFail   = r
  val branchConditional         = r
  val branchConditionalFail     = r
  val branchCall                = r
  val branchCallFail            = r
  val branchReturn              = r
  val branchReturnFail          = r
  val branchDirectionMispredict = r
  val branchTargetMispredict    = r

  when(io.branchInfo.isBranch) {
    inc(branch)
    when(io.branchInfo.isRedirect) {
      inc(branchFail)
    }.otherwise {
      inc(branchSuccess)
    }

    condInc(branchDirectionMispredict, io.branchInfo.directionMispredict)
    condInc(branchTargetMispredict, io.branchInfo.targetMispredict)

    switch(io.branchInfo.branchType) {
      is(Param.BPU.BranchType.uncond) {
        inc(branchUnconditional)
        when(io.branchInfo.isRedirect) {
          inc(branchUnconditionalFail)
        }
      }
      is(Param.BPU.BranchType.cond) {
        inc(branchConditional)
        when(io.branchInfo.isRedirect) {
          inc(branchConditionalFail)
        }
      }
      is(Param.BPU.BranchType.call) {
        inc(branchCall)
        when(io.branchInfo.isRedirect) {
          inc(branchCallFail)
        }
      }
      is(Param.BPU.BranchType.ret) {
        inc(branchReturn)
        when(io.branchInfo.isRedirect) {
          inc(branchReturnFail)
        }
      }
    }
  }
}
