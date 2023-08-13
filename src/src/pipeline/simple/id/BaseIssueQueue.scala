package pipeline.simple.id

import chisel3._
import chisel3.util._
import spec._
import pipeline.simple.MainExeNdPort
import pipeline.simple.ExeNdPort
import frontend.bundles.QueryPcBundle
import pipeline.simple.bundles.RegReadPort
import pipeline.simple.bundles.RegOccupyNdPort
import pipeline.simple.bundles.RegWakeUpNdPort
import pipeline.simple.pmu.bundles.PmuDispatchInfoBundle

abstract class BaseIssueQueue(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Module {
  val io = IO(new Bundle {
    val isFlush = Input(Bool())
    val ins = Vec(
      issueNum,
      Flipped(Decoupled(new RegReadNdPort))
    )

    val dequeuePorts = new Bundle {
      val mainExePort    = Decoupled(new MainExeNdPort)
      val simpleExePorts = Vec(pipelineNum - 1, Decoupled(new ExeNdPort))
    }

    val queryPcPort = Flipped(new QueryPcBundle)

    val regReadPorts = Vec(Param.issueInstInfoMaxNum, Vec(Param.regFileReadNum, Flipped(new RegReadPort)))
    val occupyPorts  = Output(Vec(Param.issueInstInfoMaxNum, new RegOccupyNdPort))

    val wakeUpPorts       = Input(Vec(pipelineNum + 1, new RegWakeUpNdPort))
    val pmu_dispatchInfos = Option.when(Param.usePmu)(Output(Vec(Param.pipelineNum, new PmuDispatchInfoBundle)))

  })
}
