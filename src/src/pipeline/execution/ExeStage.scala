package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort
import pipeline.execution.bundles.MemLoadStoreInfoNdPort
import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param.{ExeStageState => State}
import pipeline.execution.Alu
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.execution.bundles.JumpBranchInfoNdPort

// TODO: Add (flush ?) when jump / branch
// throws exceptions : 除零终端int
class ExeStage(readNum: Int = Param.instRegReadNum) extends Module {
  val io = IO(new Bundle {
    val exeInstPort = Input(new ExeInstNdPort)

    // `ExeStage` -> `MemStage` (next clock pulse)
    val memLoadStoreInfoPort    = Output(new MemLoadStoreInfoNdPort)
    val gprWritePort            = Output(new RfWriteNdPort)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

    // `ExeStage` -> `Pc` (no delay)
    val branchSetPort = Output(new JumpBranchInfoNdPort)

    // Pipeline control signal
    // `Cu` -> `ExeStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
    // `ExeStage` -> `Cu`
    val stallRequest = Output(Bool())
  })

  // Wb debug port connection
  val instInfoReg = Reg(new InstInfoNdPort)
  instInfoReg                    := io.instInfoPassThroughPort.in
  io.instInfoPassThroughPort.out := instInfoReg

  // Pass to the next stage in a sequential way
  val gprWriteReg = RegInit(RfWriteNdPort.default)
  io.gprWritePort := gprWriteReg

  // Start: state machine

  /** State behaviors: --> exeInst store and select
    *   - Fallback : keep inst store reg
    *   - `nonBlocking`: select and store input exeInst
    *   - `blocking` : select stored exeInst
    *
    * State transitions:
    *   - `nonBlocking`: is blocking -> `blocking`, else `nonBlocking`
    *   - `blocking` : is blocking -> `blocking`, else `nonBlocking`
    */
  val nextState = WireDefault(State.nonBlocking)
  val stateReg  = RegNext(nextState, State.nonBlocking)

  // State machine output (including fallback)
  val exeInstStoreReg = RegInit(ExeInstNdPort.default)
  exeInstStoreReg := exeInstStoreReg
  val pcStoreReg = RegInit(zeroWord)
  pcStoreReg := pcStoreReg
  val selectedExeInst = WireDefault(ExeInstNdPort.default)
  val selectedPc      = WireDefault(zeroWord)

  // Implement output function
  switch(stateReg) {
    is(State.nonBlocking) {
      selectedExeInst := io.exeInstPort
      exeInstStoreReg := io.exeInstPort
      selectedPc      := io.instInfoPassThroughPort.in.pc
      pcStoreReg      := io.instInfoPassThroughPort.in.pc
    }
    is(State.blocking) {
      selectedExeInst := exeInstStoreReg
      selectedPc      := pcStoreReg
    }
  }

  // ALU module
  val alu = Module(new Alu)

  // state machine input
  val stallRequest = WireDefault(alu.io.stallRequest)
  io.stallRequest := stallRequest
  val isBlocking = io.pipelineControlPort.stall || stallRequest

  // Next state function
  nextState := Mux(isBlocking, State.blocking, State.nonBlocking)

  // ALU input
  alu.io.aluInst.op             := selectedExeInst.exeOp
  alu.io.aluInst.leftOperand    := selectedExeInst.leftOperand
  alu.io.aluInst.rightOperand   := selectedExeInst.rightOperand
  alu.io.aluInst.jumpBranchAddr := selectedExeInst.jumpBranchAddr // also load-store imm
  alu.io.pipelineControlPort    := io.pipelineControlPort

  // ALU output

  // write-back information fallback
  gprWriteReg.en   := false.B
  gprWriteReg.addr := zeroWord
  gprWriteReg.data := zeroWord

  // write-back information selection
  when(!isBlocking) {
    gprWriteReg.en   := selectedExeInst.gprWritePort.en
    gprWriteReg.addr := selectedExeInst.gprWritePort.addr

    switch(selectedExeInst.exeSel) {
      is(Sel.logic) {
        gprWriteReg.data := alu.io.result.logic
      }
      is(Sel.shift) {
        gprWriteReg.data := alu.io.result.shift
      }
      is(Sel.arithmetic) {
        gprWriteReg.data := alu.io.result.arithmetic
      }
      is(Sel.jumpBranch) {
        gprWriteReg.data := selectedPc + 4.U
      }
    }
  }

  // MemLoadStoreInfo
  val memLoadStoreInfoReg = RegInit(MemLoadStoreInfoNdPort.default)
  io.memLoadStoreInfoPort := memLoadStoreInfoReg

  memLoadStoreInfoReg.exeOp := io.exeInstPort.exeOp
  // store : the data to write
  // preld, dbar, ibar : hint
  memLoadStoreInfoReg.data  := io.exeInstPort.rightOperand
  memLoadStoreInfoReg.vaddr := (io.exeInstPort.leftOperand + io.exeInstPort.loadStoreImm)

  // branch set
  io.branchSetPort := alu.io.result.jumpBranchInfo
}
