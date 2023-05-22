package pipeline.execution

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ExeInstNdPort
import spec.ExeInst.Sel
import spec._
import control.bundles.PipelineControlNDPort
import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec.Param.{ExeStageState => State}
import pipeline.execution.Alu
import pipeline.writeback.bundles.InstInfoNdPort
import pipeline.execution.bundles.JumpBranchInfoNdPort
import common.bundles.PcSetPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import memory.bundles.MemRequestNdPort
import common.enums.ReadWriteSel

// TODO: MemLoadStoreInfoNdPort is deprecated

// TODO: Add (flush ?) when jump / branch
// throw exception: 地址未对齐 ale
class ExeStage(readNum: Int = Param.instRegReadNum) extends Module {
  val io = IO(new Bundle {
    val exeInstPort = Input(new ExeInstNdPort)

    // `ExeStage` -> `AddrTransStage` (next clock pulse)
    val memAccessPort           = Output(new MemRequestNdPort)
    val gprWritePort            = Output(new RfWriteNdPort)
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

    // `ExeStage` -> `Pc` (no delay)
    val branchSetPort = Output(new PcSetPort)

    // Scoreboard
    val freePorts = Output(new ScoreboardChangeNdPort)

    // Pipeline control signal
    // `Cu` -> `ExeStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)
    // `ExeStage` -> `Cu`
    val stallRequest = Output(Bool())
  })

  // Indicate the availability in scoreboard
  // 当再ExeStage计算出结果，则free scoreboard
  val freePortEn = RegInit(false.B)
  freePortEn        := false.B
  io.freePorts.en   := freePortEn
  io.freePorts.addr := io.gprWritePort.addr

  // Wb debug port connection
  val instInfoReg = Reg(new InstInfoNdPort)
  io.instInfoPassThroughPort.out := instInfoReg

  // Pass to the next stage in a sequential way
  val gprWriteReg = RegInit(RfWriteNdPort.default)
  io.gprWritePort := gprWriteReg

  val memRequestReg = RegInit(MemRequestNdPort.default)
  io.memAccessPort := memRequestReg

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
      selectedExeInst := io.exeInstPort
      exeInstStoreReg := io.exeInstPort
      selectedPc      := io.instInfoPassThroughPort.in.pc
      pcStoreReg      := io.instInfoPassThroughPort.in.pc

      // val inMemLoadStoreInfo = Wire(new MemLoadStoreInfoNdPort)
      // inMemLoadStoreInfo.data  := io.exeInstPort.rightOperand
      // inMemLoadStoreInfo.vaddr := (io.exeInstPort.leftOperand + io.exeInstPort.loadStoreImm)
      // selectedMemLoadStoreInfo := inMemLoadStoreInfo
      // memLoadStoreInfoStoreReg := inMemLoadStoreInfo
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
        io.freePorts.en  := gprWriteReg.en
        gprWriteReg.data := alu.io.result.logic
      }
      is(Sel.shift) {
        io.freePorts.en  := gprWriteReg.en
        gprWriteReg.data := alu.io.result.shift
      }
      is(Sel.arithmetic) {
        io.freePorts.en  := gprWriteReg.en
        gprWriteReg.data := alu.io.result.arithmetic
      }
      is(Sel.jumpBranch) {
        io.freePorts.en  := gprWriteReg.en
        gprWriteReg.data := selectedPc + 4.U
      }
    }

    switch(selectedExeInst.exeOp) {
      is(ExeInst.Op.csrrd) {
        io.freePorts.en  := gprWriteReg.en
        gprWriteReg.data := selectedExeInst.csrData
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
  val isALE = WireDefault(false.B)
  instInfoReg.exceptionRecords(Csr.ExceptionIndex.ale) := isALE

  when(!isBlocking) {
    memRequestReg.isValid    := (memReadEn || memWriteEn) && !isALE
    memRequestReg.addr       := Cat(loadStoreAddr(wordLength - 1, 2), 0.U(2.W))
    memRequestReg.write.data := selectedExeInst.rightOperand
    memRequestReg.isUnsigned := memLoadUnsigned
    memRequestReg.rw         := Mux(memWriteEn, ReadWriteSel.write, ReadWriteSel.read)
    // mask
    val maskEncode = loadStoreAddr(1, 0)
    switch(selectedExeInst.exeOp) {
      is(ExeInst.Op.ld_b, ExeInst.Op.ld_bu, ExeInst.Op.st_b) {
        memRequestReg.write.mask := Mux(
          maskEncode(1),
          Mux(maskEncode(0), "b1000".U, "b0100".U),
          Mux(maskEncode(0), "b0010".U, "b0001".U)
        )
      }
      is(ExeInst.Op.ld_h, ExeInst.Op.ld_hu, ExeInst.Op.st_h) {
        when(maskEncode(0)) {
          isALE := true.B // 未对齐
        }
        memRequestReg.write.mask := Mux(maskEncode(1), "b1100".U, "b0011".U)
      }
      is(ExeInst.Op.ld_w, ExeInst.Op.ll, ExeInst.Op.st_w, ExeInst.Op.sc) {
        isALE                    := maskEncode.orR
        memRequestReg.write.mask := "b1111".U
      }
    }
  }

  /** CsrWrite
    */
  when(!isBlocking) {
    instInfoReg := instInfoStoreReg
    def csrWriteData = instInfoReg.csrWritePort.data
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

  /** InstInfo Csr read or write info
    */

  // clear
  when(io.pipelineControlPort.clear) {
    gprWriteReg := RfWriteNdPort.default
    InstInfoNdPort.setDefault(instInfoReg)
    memRequestReg := MemRequestNdPort.default
  }
  // flush all regs
  when(io.pipelineControlPort.flush) {
    gprWriteReg := RfWriteNdPort.default
    InstInfoNdPort.setDefault(instInfoReg)
    memRequestReg   := MemRequestNdPort.default
    stateReg        := State.nonBlocking
    exeInstStoreReg := ExeInstNdPort.default
    pcStoreReg      := zeroWord
  }
}
