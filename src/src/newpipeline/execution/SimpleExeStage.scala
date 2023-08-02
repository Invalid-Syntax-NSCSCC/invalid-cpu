package newpipeline.execution

import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import control.csrBundles.{EraBundle, LlbctlBundle}
import control.enums.ExceptionPos
import memory.bundles.CacheMaintenanceControlNdPort
import common.BaseStage
import pipeline.execution.ExeNdPort
import pipeline.memory.enums.CacheMaintenanceTargetType
import spec.Param.isDiffTest
import spec._
import spec.ExeInst.Sel

import scala.collection.immutable
import pipeline.memory.AddrTransNdPort
import pipeline.memory.bundles.MemRequestNdPort
import pipeline.commit.bundles.InstInfoNdPort
import memory.bundles.TlbMaintenanceNdPort
import pipeline.memory.bundles.CacheMaintenanceInstNdPort
import pipeline.commit.WbNdPort
import pipeline.execution.Alu
import common.bundles.BackendRedirectPcNdPort
import control.bundles.CsrWriteNdPort
import control.bundles.StableCounterReadPort
import control.bundles.CsrReadPort
import frontend.bundles.ExeFtqPort
import pipeline.rob.bundles.RobQueryPcPort
import common.common.BaseStageWOOutReg

// support all insts
class SimpleExeStage
    extends BaseStageWOOutReg(
      new ExeNdPort,
      new WbNdPort,
      ExeNdPort.default,
      None
    ) {
  val out  = io.out.bits
  val peer = io.peer.get

  // ALU module
  val alu = Module(new Alu)

  isComputed        := alu.io.outputValid
  out               := DontCare
  out.instInfo      := selectedIn.instInfo
  out.gprWrite.en   := selectedIn.gprWritePort.en
  out.gprWrite.addr := selectedIn.gprWritePort.addr
  io.out.valid      := isComputed && selectedIn.instInfo.isValid

  // alu

  // ALU input
  alu.io.isFlush                := io.isFlush
  alu.io.inputValid             := selectedIn.instInfo.isValid
  alu.io.aluInst.op             := selectedIn.exeOp
  alu.io.aluInst.leftOperand    := selectedIn.leftOperand
  alu.io.aluInst.rightOperand   := selectedIn.rightOperand
  alu.io.aluInst.jumpBranchAddr := DontCare

  out.gprWrite.data := DontCare

  switch(selectedIn.exeSel) {
    is(Sel.logic) {
      out.gprWrite.data := alu.io.result.logic
    }
    is(Sel.shift) {
      out.gprWrite.data := alu.io.result.shift
    }
    is(Sel.arithmetic) {
      out.gprWrite.data := alu.io.result.arithmetic
    }
  }
}
