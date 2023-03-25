package pipeline.execution

import chisel3._
import chisel3.util._
import pipeline.execution.bundles.{AluInstNdPort, AluResultNdPort}
import spec._
import ExeInst.Op
import pipeline.ctrl.bundles.PipelineControlNDPort
import pipeline.execution.bundles.JumpBranchInfoNdPort
import spec.Param.{AluState => State}

// Attention: if stallRequest is true, the exeInstPort needs to keep unchange
class Alu extends Module {
  val io = IO(new Bundle {
    val aluInst = Input(new AluInstNdPort)
    val result  = Output(new AluResultNdPort)

    val pipelineControlPort  = Input(new PipelineControlNDPort)
    val stallRequest         = Output(Bool())
    val divisorZeroException = Output(Bool())
  })

  io.result := AluResultNdPort.default

  def lop = io.aluInst.leftOperand

  def rop = io.aluInst.rightOperand

  val stallRequest = WireDefault(false.B)
  io.stallRequest := stallRequest

  /** State machine
    *
    * State behaviors for shift, logic, jump and
    *   - `nonBlocking`: permit mulStage and divStage start running
    *   - `blocking`: forbidden mulStage and divStage start running
    *
    * State transition
    *   - `nonBlocking`: blocking from other stage -> `nonblocking` else `blocking`
    *   - `blocking` : blocking from other stage -> `nonblocking` else `blocking`
    */

  val nextState = WireDefault(State.nonBlocking)
  val stateReg  = RegNext(nextState, State.nonBlocking)

  nextState := Mux(io.pipelineControlPort.stall, State.blocking, State.nonBlocking)

  /** Result definition
    */

  val logic = WireDefault(zeroWord)

  val shift = WireDefault(zeroWord)

  val jumpBranchInfo = WireDefault(JumpBranchInfoNdPort.default)

  // computed with one cycle
  val arithmetic = WireDefault(zeroWord)

  val computedResult = WireDefault(AluResultNdPort.default)
  io.result := computedResult

  when(!(io.pipelineControlPort.stall && stallRequest)) {
    computedResult.arithmetic     := arithmetic
    computedResult.logic          := logic
    computedResult.jumpBranchInfo := jumpBranchInfo
    computedResult.shift          := shift
  }

  /** Logic computation
    */
  switch(io.aluInst.op) {
    is(Op.nor) {
      logic := ~(lop | rop)
    }
    is(Op.and) {
      logic := lop & rop
    }
    is(Op.or) {
      logic := lop | rop
    }
    is(Op.xor) {
      logic := lop ^ rop
    }
  }

  /** shift computation
    */
  switch(io.aluInst.op) {
    is(Op.sll) {
      shift := lop << rop(4, 0);
    }
    is(Op.srl) {
      shift := lop >> rop(4, 0);
    }
    is(Op.sra) {
      shift := (lop.asSInt << rop(4, 0)).asUInt
    }
  }

  /** jump and branch computation
    */
  switch(io.aluInst.op) {
    is(Op.b, Op.bl) {
      jumpBranchInfo.en     := true.B
      jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
    }
    is(Op.jirl) {
      jumpBranchInfo.en     := true.B
      jumpBranchInfo.pcAddr := lop + io.aluInst.jumpBranchAddr
    }
    is(Op.beq) {
      when(lop === rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bne) {
      when(lop =/= rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.blt) {
      when(lop.asSInt < rop.asSInt) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bge) {
      when(lop.asSInt >= rop.asSInt) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bltu) {
      when(lop < rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
    is(Op.bgeu) {
      when(lop >= rop) {
        jumpBranchInfo.en     := true.B
        jumpBranchInfo.pcAddr := io.aluInst.jumpBranchAddr
      }
    }
  }

  /** arithmetic computation
    */

  // mul

  val useMul = WireDefault(
    VecInit(
      ExeInst.Op.mul,
      ExeInst.Op.mulh,
      ExeInst.Op.mulhu
    ).contains(io.aluInst.op)
  )

  val mulStart = WireDefault(false.B)

  val mulStage = Module(new Mul)
  mulStage.io.mulInst.valid             := mulStart
  mulStage.io.mulInst.bits.op           := io.aluInst.op
  mulStage.io.mulInst.bits.leftOperand  := lop
  mulStage.io.mulInst.bits.rightOperand := rop

  mulStage.io.mulResult.ready := DontCare

  // mulStart := useMul && !mulStage.io.mulResult.valid && !io.pipelineControlPort.stall
  switch(stateReg) {
    is(State.nonBlocking) {
      mulStart := useMul && !mulStage.io.mulResult.valid && !io.pipelineControlPort.stall
    }
    is(State.blocking) {
      mulStart := false.B
    }
  }

  val mulResult = WireDefault(mulStage.io.mulResult.bits)

  val mulResultStoreReg = RegInit(0.U(doubleWordLength.W))
  mulResultStoreReg := mulResultStoreReg
  when(mulStage.io.mulResult.valid) {
    mulResultStoreReg := mulResult
  }

  val selectedMulResult = Mux(
    mulStage.io.mulResult.valid,
    mulResult,
    mulResultStoreReg
  )

  // Div

  val useDiv = WireDefault(
    VecInit(
      ExeInst.Op.div,
      ExeInst.Op.divu,
      ExeInst.Op.mod,
      ExeInst.Op.modu
    ).contains(io.aluInst.op)
  )

  val divStart = WireDefault(false.B)

  val divStage = Module(new Div)
  divStage.io.divInst.valid             := divStart
  divStage.io.divInst.bits.op           := io.aluInst.op
  divStage.io.divInst.bits.leftOperand  := lop
  divStage.io.divInst.bits.rightOperand := rop

  divStage.io.divResult.ready := DontCare

  val divisorValid = WireDefault(rop.orR)
  io.divisorZeroException := !divisorValid && useDiv

  // divStart := useDiv && !divStage.io.isRunning && !divStage.io.divResult.valid && divisorValid && !io.pipelineControlPort.stall
  switch(stateReg) {
    is(State.nonBlocking) {
      divStart := useDiv && !divStage.io.isRunning && !divStage.io.divResult.valid && divisorValid
    }
    is(State.blocking) {
      divStart := false.B
    }
  }

  val quotient  = WireDefault(divStage.io.divResult.bits.quotient)
  val remainder = WireDefault(divStage.io.divResult.bits.remainder)

  val quotientStoreReg  = RegInit(zeroWord)
  val remainderStoreReg = RegInit(zeroWord)
  quotientStoreReg  := quotientStoreReg
  remainderStoreReg := remainderStoreReg
  when(divStage.io.divResult.valid) {
    quotientStoreReg  := quotient
    remainderStoreReg := remainder
  }

  val selectedQuotient  = Mux(divStage.io.divResult.valid, quotient, quotientStoreReg)
  val selectedRemainder = Mux(divStage.io.divResult.valid, remainder, remainderStoreReg)

  stallRequest := (mulStart || divStart || divStage.io.isRunning)

  when(!stallRequest) {
    switch(io.aluInst.op) {
      is(Op.add) {
        arithmetic := (lop.asSInt + rop.asSInt).asUInt
      }
      is(Op.sub) {
        arithmetic := (lop.asSInt - rop.asSInt).asUInt
      }
      is(Op.slt) {
        arithmetic := (lop.asSInt < rop.asSInt).asUInt
      }
      is(Op.sltu) {
        arithmetic := (lop < rop).asUInt
      }
      is(Op.mul) {
        arithmetic := selectedMulResult(wordLength - 1, 0)
      }
      is(Op.mulh, Op.mulhu) {
        arithmetic := selectedMulResult(doubleWordLength - 1, wordLength)
      }
      is(Op.div, Op.divu) {
        arithmetic := selectedQuotient
      }
      is(Op.mod, Op.modu) {
        arithmetic := selectedRemainder
      }
    }
  }
}
