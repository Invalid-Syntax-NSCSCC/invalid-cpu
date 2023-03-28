package pipeline.dispatch

import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.{DecodeOutNdPort, DecodePort, InstInfoBundle, IssuedInfoNdPort, ScoreboardChangeNdPort}
import pipeline.dispatch.decode.{
  Decoder,
  Decoder_2R,
  Decoder_2RI12,
  Decoder_2RI14,
  Decoder_2RI16,
  Decoder_3R,
  Decoder_4R,
  Decoder_special
}
import spec._
import pipeline.ctrl.bundles.PipelineControlNDPort
import spec.Param.{IssueStageState => State}

class IssueStage(scoreChangeNum: Int = Param.scoreboardChangeNum) extends Module {
  val io = IO(new Bundle {
    // `InstQueue` -> `IssueStage`
    val fetchInstInfoPort = Flipped(Decoupled(new InstInfoBundle))

    // `IssueStage` <-> `Scoreboard`
    val occupyPorts = Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort))
    val regScores   = Input(Vec(Count.reg, Bool()))

    // `IssueStage` -> `RegReadStage` (next clock pulse)
    val issuedInfoPort = Output(new IssuedInfoNdPort)

    // pipeline control signal
    // `Cu` -> `IssueStage`
    val pipelineControlPort = Input(new PipelineControlNDPort)

    val wbDebugInst = Output(UInt(Width.Reg.data))

    // `Issue Stage` -> `Cu`
    val instInvalidException = Output(Bool())
  })

  // Pass to the next stage in a sequential way
  val issuedInfoReg = RegInit(IssuedInfoNdPort.default)
  io.issuedInfoPort := issuedInfoReg

  // Start: state machine

  /** State behaviors:
    *   - Fallback: keep inst store reg, no issue, no fetch (pre)
    *   - `nonBlocking`: fetch (pre)
    *     - If can fetch: store inst to reg
    *       - If blocking: (nothing else)
    *       - if still non-blocking: issue
    *     - If cannot fetch: (nothing else)
    *   - `blocking`:
    *     - If still blocking: (nothing else)
    *     - If non-blocking: issue
    *
    * State behaviors:
    *   - `nonBlocking`:
    *     - If can fetch: decode from port
    *     - If cannot fetch: decode nop
    *   - `blocking`: decode from inst store reg
    *
    * State transitions:
    *   - `nonBlocking`: is blocking -> `blocking`, else `nonBlocking`
    *   - `blocking`: is blocking -> `blocking`, else `nonBlocking`
    */
  val nextState = WireInit(State.nonBlocking)
  val stateReg  = RegInit(State.nonBlocking)
  stateReg := nextState

  // State machine output (including fallback)
  val instStoreReg = RegInit(InstInfoBundle.default)
  instStoreReg := instStoreReg
  val selectedInstInfo = WireDefault(InstInfoBundle.default)
  val isAllowIssue     = WireDefault(false.B)
  val isFetch          = WireDefault(false.B)

  // Connect state machine output to I/O
  io.fetchInstInfoPort.ready := isFetch

  // Implement output function
  io.instInvalidException := false.B
  switch(stateReg) {
    is(State.nonBlocking) {
      isFetch := true.B
      when(io.fetchInstInfoPort.valid) {
        selectedInstInfo        := io.fetchInstInfoPort.bits
        instStoreReg            := io.fetchInstInfoPort.bits
        io.instInvalidException := !isInstValid
      }
    }
    is(State.blocking) {
      selectedInstInfo := instStoreReg
    }
  }

  // Decode

  // Select a decoder
  val decoders = Seq(
    Module(new Decoder_2RI12),
    Module(new Decoder_2RI14),
    Module(new Decoder_2RI16),
    Module(new Decoder_2R),
    Module(new Decoder_3R),
    Module(new Decoder_4R),
    Module(new Decoder_special)
  )
  decoders.foreach(_.io.instInfoPort := selectedInstInfo)

  val decoderWires = Wire(Vec(decoders.length, new DecodeOutNdPort))
  decoderWires.zip(decoders).foreach {
    case (port, decoder) =>
      port := decoder.io.out
  }
  val decoderIndex    = WireDefault(OHToUInt(Cat(decoderWires.map(_.isMatched).reverse)))
  val selectedDecoder = WireDefault(decoderWires(decoderIndex))

  // State machine input
  /** Determine blocking:
    *   - If stall signal: blocking
    *   - If not stall:
    *     - If inst valid:
    *       - If no data hazard: non-blocking
    *       - If data hazard: blocking
    *     - If inst invalid:
    *       - non-blocking
    */
  val isInstValid = WireDefault(decoderWires.map(_.isMatched).reduce(_ || _))
  val isScoreboardBlocking = WireDefault(selectedDecoder.info.gprReadPorts.map { port =>
    port.en && io.regScores(port.addr)
  }.reduce(_ || _))
  val isBlocking = WireDefault(
    io.pipelineControlPort.stall || (
      isInstValid && isScoreboardBlocking
    )
  )

  // Implement output function
  switch(stateReg) {
    is(State.nonBlocking) {
      when(io.fetchInstInfoPort.valid && !isBlocking) {
        isAllowIssue := true.B
      }
    }
    is(State.blocking) {
      when(!isBlocking) {
        isAllowIssue := true.B
      }
    }
  }

  // Next state function
  nextState := Mux(isBlocking, State.blocking, State.nonBlocking)

  // End: state machine

  // Fallback for no operation
  issuedInfoReg.isValid              := isAllowIssue && isInstValid
  issuedInfoReg.info                 := DontCare
  issuedInfoReg.info.gprWritePort.en := false.B
  io.occupyPorts.foreach { port =>
    port.en   := false.B
    port.addr := DontCare
  }
  val wbDebugInstReg = RegInit(0.U(Width.Reg.data))
  io.wbDebugInst := wbDebugInstReg
  wbDebugInstReg := 0.U

  // Issue pre-execution instruction

  when(isAllowIssue) {
    // Indicate the occupation in scoreboard
    instStoreReg := InstInfoBundle.default // Patch for preventing reissue an inst itself
    io.occupyPorts.zip(Seq(selectedDecoder.info.gprWritePort)).foreach {
      case (occupyPort, accessInfo) =>
        occupyPort.en   := accessInfo.en
        occupyPort.addr := accessInfo.addr
    }

    issuedInfoReg.info := selectedDecoder.info
    wbDebugInstReg     := selectedInstInfo.inst
  }
}
