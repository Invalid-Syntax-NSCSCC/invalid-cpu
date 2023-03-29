package pipeline.ctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param
import pipeline.ctrl.bundles.PipelineControlNDPort
import spec.PipelineStageIndex
import pipeline.writeback.bundles.InstInfoNdPort
import common.bundles.RfWriteNdPort
import common.bundles.PassThroughPort
import pipeline.ctrl.bundles.CsrWriteNdPort

// TODO: Add stall to frontend ?
// TODO: Add flush to stages
// TODO: Add deal exceptions
class Cu(ctrlControlNum: Int = Param.ctrlControlNum, writeNum: Int = Param.csrRegsWriteNum) extends Module {
  val io = IO(new Bundle {
    // `WbStage` -> `Cu`
    val gprWritePassThroughPort = new PassThroughPort(new RfWriteNdPort)
    val instInfoPort            = Input(new InstInfoNdPort)
    // `Cu` -> `Csr`
    val csrWritePorts = Output(Vec(writeNum, new CsrWriteNdPort))
    // `ExeStage` -> `Cu`
    val exeStallRequest = Input(Bool())
    // `MemStage` -> `Cu`
    val memStallRequest = Input(Bool())
    // `Cu` -> `IssueStage`, `RegReadStage`, `ExeStage`, `MemStage`
    val pipelineControlPorts = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
  })

  /** Stall
    */

  io.pipelineControlPorts.foreach(_ := PipelineControlNDPort.default)
  // `ExeStage` --stall--> `IssueStage`, `RegReadStage` (DONT STALL ITSELF)
  Seq(PipelineStageIndex.issueStage, PipelineStageIndex.regReadStage)
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.exeStallRequest)
  // `MemStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage`  (DONT STALL ITSELF)
  Seq(PipelineStageIndex.issueStage, PipelineStageIndex.regReadStage, PipelineStageIndex.exeStage)
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.memStallRequest)

  /** Exception
    */
  val hasException = WireDefault(io.instInfoPort.exceptionRecords.reduce(_ || _))

  /** Write Regfile
    */
  // temp
  io.gprWritePassThroughPort.out := Mux(
    hasException,
    io.gprWritePassThroughPort.in,
    RfWriteNdPort.default
  )
}
