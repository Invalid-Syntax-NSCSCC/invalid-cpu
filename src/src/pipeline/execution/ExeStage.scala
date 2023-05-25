package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._
import control.bundles.PipelineControlNdPort
import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals._
import spec.Param.{ExeStageState => State}
import pipeline.execution.Alu
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.execution.bundles.JumpBranchInfoNdPort
import common.bundles.PcSetPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import common.enums.ReadWriteSel
import pipeline.mem.bundles.MemRequestNdPort
import pipeline.execution.bundles.ExeResultPort

// TODO: Add (flush ?) when jump / branch
// throw exception: 地址未对齐 ale
class ExeStage extends Module {
  val io = IO(new Bundle {
    val exeInstPort = Flipped(Decoupled(new ExeInstNdPort))

    // `ExeStage` -> `AddrTransStage` (next clock pulse)
    val exeResultPort = Decoupled(new ExeResultPort)

    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

    // `ExeStage` -> `Pc` (no delay)
    val branchSetPort = Output(new PcSetPort)

    // Scoreboard
    // val freePorts = Output(new ScoreboardChangeNdPort)

    // Pipeline control signal
    // `Cu` -> `ExeStage`
    val pipelineControlPort = Input(new PipelineControlNdPort)
  })

  // Indicate the availability in scoreboard
  // 当再ExeStage计算出结果，则free scoreboard
  // val freePortEn = RegInit(false.B)
  // freePortEn        := false.B
  // io.freePorts.en   := freePortEn
  // io.freePorts.addr := io.gprWritePort.addr

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg

  // Pass to the next stage in a sequential way
  val exeResultReg = RegInit(ExeResultPort.default)
  io.exeResultPort.bits := exeResultReg
  // val gprWriteReg = RegInit(RfWriteNdPort.default)
  // io.gprWritePort := gprWriteReg

  // val memRequestReg = RegInit(MemRequestNdPort.default)
  // io.memAccessPort := memRequestReg

  // Start: state machine

  /** State behaviors: --> exeInst store and select
    *   - Fallback : keep inst store reg
    *   - `nonBlocking`: select and store input exeInst output valid = true
    *   - `blocking` : select stored exeInst output valid = false
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
  // val memLoadStoreInfoStoreReg = RegInit(MemLoadStoreInfoNdPort.default)
  // memLoadStoreInfoStoreReg := memLoadStoreInfoStoreReg
  val instInfoStoreReg = Reg(new InstInfoNdPort)
  instInfoStoreReg := instInfoStoreReg

  val selectedExeInst = WireDefault(ExeInstNdPort.default)
  val selectedPc      = WireDefault(zeroWord)
  // val selectedMemLoadStoreInfo = WireDefault(MemLoadStoreInfoNdPort.default)
  val selectedInstInfo = WireDefault(instInfoStoreReg)

  // Implement output function
  switch(stateReg) {
    is(State.nonBlocking) {
      selectedExeInst  := io.exeInstPort.bits
      exeInstStoreReg  := io.exeInstPort.bits
      selectedPc       := io.instInfoPassThroughPort.in.pc
      pcStoreReg       := io.instInfoPassThroughPort.in.pc
      selectedInstInfo := io.instInfoPassThroughPort.in
      instInfoStoreReg := io.instInfoPassThroughPort.in
    }
    is(State.blocking) {
      selectedExeInst := exeInstStoreReg
      selectedPc      := pcStoreReg
      // selectedMemLoadStoreInfo := memLoadStoreInfoStoreReg
      selectedInstInfo := instInfoStoreReg
    }
  }

  // ALU module
  val alu = Module(new Alu)

  // state machine input
  val isBlocking = !io.exeResultPort.ready || alu.io.blockRequest

  // Next state function
  nextState := Mux(isBlocking, State.blocking, State.nonBlocking)

  // ALU input
  alu.io.aluInst.op             := selectedExeInst.exeOp
  alu.io.aluInst.leftOperand    := selectedExeInst.leftOperand
  alu.io.aluInst.rightOperand   := selectedExeInst.rightOperand
  alu.io.aluInst.jumpBranchAddr := selectedExeInst.jumpBranchAddr // also load-store imm
  alu.io.isBlocking             := isBlocking

  // ALU output

  // write-back information fallback
  exeResultReg.gprWritePort.en   := false.B
  exeResultReg.gprWritePort.addr := zeroWord
  exeResultReg.gprWritePort.data := zeroWord

  // write-back information selection
  when(!isBlocking) {
    exeResultReg.gprWritePort.en   := selectedExeInst.gprWritePort.en
    exeResultReg.gprWritePort.addr := selectedExeInst.gprWritePort.addr

    switch(selectedExeInst.exeSel) {
      is(Sel.logic) {
        // io.freePorts.en  := gprWriteReg.en
        exeResultReg.gprWritePort.data := alu.io.result.logic
      }
      is(Sel.shift) {
        // io.freePorts.en  := gprWriteReg.en
        exeResultReg.gprWritePort.data := alu.io.result.shift
      }
      is(Sel.arithmetic) {
        // io.freePorts.en  := gprWriteReg.en
        exeResultReg.gprWritePort.data := alu.io.result.arithmetic
      }
      is(Sel.jumpBranch) {
        // io.freePorts.en  := gprWriteReg.en
        exeResultReg.gprWritePort.data := selectedPc + 4.U
      }
    }

    switch(selectedExeInst.exeOp) {
      is(ExeInst.Op.csrrd) {
        // io.freePorts.en  := gprWriteReg.en
        exeResultReg.gprWritePort.data := selectedExeInst.csrData
      }
    }
  }

  /** MemAccess
    */
  val loadStoreAddr = WireDefault(selectedExeInst.leftOperand + selectedExeInst.loadStoreImm)
  val memReadEn = WireDefault(
    VecInit(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.ld_w, ExeInst.Op.ll)
      .contains(selectedExeInst.exeOp)
  )
  val memWriteEn = WireDefault(
    VecInit(ExeInst.Op.st_b, ExeInst.Op.st_h, ExeInst.Op.st_w, ExeInst.Op.sc)
      .contains(selectedExeInst.exeOp)
  )
  val memLoadUnsigned = WireDefault(VecInit(ExeInst.Op.ld_bu, ExeInst.Op.ld_hu).contains(selectedExeInst.exeOp))
  // 指令未对齐
  val isAle = WireDefault(false.B)
  instInfoReg.exceptionRecords(Csr.ExceptionIndex.ale) := isAle

  when(!isBlocking) {
    exeResultReg.memAccessPort.isValid         := (memReadEn || memWriteEn) && !isAle
    exeResultReg.memAccessPort.addr            := Cat(loadStoreAddr(wordLength - 1, 2), 0.U(2.W))
    exeResultReg.memAccessPort.write.data      := selectedExeInst.rightOperand
    exeResultReg.memAccessPort.read.isUnsigned := memLoadUnsigned
    exeResultReg.memAccessPort.rw              := Mux(memWriteEn, ReadWriteSel.write, ReadWriteSel.read)
    // mask
    val maskEncode = loadStoreAddr(1, 0)
    switch(selectedExeInst.exeOp) {
      is(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.st_b) {
        exeResultReg.memAccessPort.mask := Mux(
          maskEncode(1),
          Mux(maskEncode(0), "b1000".U, "b0100".U),
          Mux(maskEncode(0), "b0010".U, "b0001".U)
        )
      }
      is(ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.st_h) {
        when(maskEncode(0)) {
          isAle := true.B // 未对齐
        }
        exeResultReg.memAccessPort.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
      }
      is(ExeInst.Op.ld_w, ExeInst.Op.ll, ExeInst.Op.st_w, ExeInst.Op.sc) {
        isAle                           := maskEncode.orR
        exeResultReg.memAccessPort.mask := "b1111".U
      }
    }
  }

  /** CsrWrite
    */
  def csrWriteData = instInfoReg.csrWritePort.data
  when(!isBlocking) {
    instInfoReg := instInfoStoreReg

    switch(selectedExeInst.exeOp) {
      is(ExeInst.Op.csrwr) {
        csrWriteData := selectedExeInst.csrData
      }
      is(ExeInst.Op.csrxchg) {
        // lop: write value  rop: mask
        val gprWriteDataVec = Wire(Vec(wordLength, Bool()))
        selectedExeInst.leftOperand.asBools
          .lazyZip(selectedExeInst.rightOperand.asBools)
          .lazyZip(selectedExeInst.csrData.asBools)
          .lazyZip(gprWriteDataVec)
          .foreach {
            case (write, mask, origin, target) =>
              target := Mux(mask, write, origin)
          }
        csrWriteData := gprWriteDataVec.asUInt
      }
    }
  }

  // branch set
  io.branchSetPort := alu.io.result.jumpBranchInfo

  // ready-valid
  io.exeInstPort.ready   := !isBlocking
  io.exeResultPort.valid := stateReg === State.nonBlocking

  /** InstInfo Csr read or write info
    */

  // Flush
  when(io.pipelineControlPort.flush) {
    exeResultReg.gprWritePort.en := false.B
    InstInfoNdPort.invalidate(instInfoReg)
    exeResultReg.memAccessPort.isValid := false.B

    stateReg        := State.nonBlocking
    exeInstStoreReg := ExeInstNdPort.default
    pcStoreReg      := zeroWord

    io.exeInstPort.ready   := false.B
    io.exeResultPort.valid := false.B
  }
}
