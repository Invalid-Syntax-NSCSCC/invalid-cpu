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
class Cu(
  ctrlControlNum: Int = Param.ctrlControlNum,
  writeNum:       Int = Param.csrRegsWriteNum,
  dispatchNum:    Int = Param.dispatchInstNum)
    extends Module {
  val io = IO(new Bundle {
    // `WbStage` -> `Cu`
    val gprWritePassThroughPorts = new PassThroughPort(Vec(dispatchNum, new RfWriteNdPort))
    val instInfoPorts            = Input(Vec(dispatchNum, new InstInfoNdPort))
    // `Cu` -> `Csr`
    val csrWritePorts = Output(Vec(writeNum, new CsrWriteNdPort))
    // `ExeStage` -> `Cu`
    val exeStallRequest = Input(Bool())
    // `MemStage` -> `Cu`
    val memStallRequest = Input(Bool())
    // `Cu` -> `IssueStage`, `RegReadStage`, `ExeStage`, `MemStage`
    val pipelineControlPorts = Output(Vec(ctrlControlNum, new PipelineControlNDPort))
  })

  /** Stall 暂停流水线前面部分
    */

  io.pipelineControlPorts.foreach(_ := PipelineControlNDPort.default)
  // `ExeStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage` (STALL ITSELF)
  Seq(PipelineStageIndex.issueStage, PipelineStageIndex.regReadStage, PipelineStageIndex.exeStage)
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.exeStallRequest)
  // `MemStage` --stall--> `IssueStage`, `RegReadStage`, `ExeStage`, `MemStage`  (STALL ITSELF)
  Seq(
    PipelineStageIndex.issueStage,
    PipelineStageIndex.regReadStage,
    PipelineStageIndex.exeStage,
    PipelineStageIndex.memStage
  )
    .map(io.pipelineControlPorts(_))
    .foreach(_.stall := io.memStallRequest)

  /** clear
    *
    * Assume A -> B, A is stall but B is not stall. Give A a clear signal to clear its output
    */

  Seq(
    PipelineStageIndex.issueStage,
    PipelineStageIndex.regReadStage,
    PipelineStageIndex.exeStage,
    PipelineStageIndex.memStage
  ).map(io.pipelineControlPorts(_)).reduce { (prev, next) =>
    prev.clear := prev.stall && !next.stall
    next
  }

  /** Exception
    */
  val hasException = WireDefault(io.instInfoPorts.map(_.exceptionRecords.reduce(_ || _)).reduce(_ || _))

  /** Write Regfile
    */
  // temp
  io.gprWritePassThroughPorts.out(0) := Mux(
    hasException,
    io.gprWritePassThroughPorts.in(0),
    RfWriteNdPort.default
  )

  io.csrWritePorts.foreach { port => port := CsrWriteNdPort.default }

  /** flush
    */

  val flush = WireDefault(hasException)
  io.pipelineControlPorts.foreach(_.flush := flush)
}
