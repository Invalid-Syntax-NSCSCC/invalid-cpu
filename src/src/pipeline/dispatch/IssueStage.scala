// package pipeline.dispatch

// import chisel3._
// import chisel3.util._
// import pipeline.dispatch.bundles.{InstInfoBundle, IssuedInfoNdPort, ScoreboardChangeNdPort}
// import spec._
// import control.bundles.PipelineControlNdPort
// import pipeline.dispatch.enums.{IssueStageState => State}
// import pipeline.writeback.bundles.InstInfoNdPort
// import common.bundles.PassThroughPort
// import pipeline.queue.bundles.{DecodeOutNdPort, DecodePort}

// // throws exceptions: 指令不存在异常ine
// class IssueStage(scoreChangeNum: Int = Param.regFileWriteNum) extends Module {
//   val io = IO(new Bundle {
//     // `InstQueue` -> `IssueStage`
//     val fetchInstDecodePort = Flipped(Decoupled(new DecodeOutNdPort))

//     // `IssueStage` <-> `Scoreboard`
//     val occupyPorts = Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort))
//     val regScores   = Input(Vec(Count.reg, Bool()))

//     // `IssueStage` <-> `Scoreboard(csr)`
//     val csrOccupyPorts = Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort))
//     val csrRegScores   = Input(Vec(Count.csrReg, Bool()))

//     // `IssueStage` -> `RegReadStage` (next clock pulse)
//     val issuedInfoPort = Output(new IssuedInfoNdPort)
//     // val instInfoPort   = Output(new InstInfoNdPort)
//     val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

//     // pipeline control signal
//     // `Cu` -> `IssueStage`
//     val pipelineControlPort = Input(new PipelineControlNdPort)
//   })

//   // Wb debug port connection
//   val instInfoReg = Reg(new InstInfoNdPort)
//   InstInfoNdPort.invalidate(instInfoReg)
//   // val instInfoReg = RegInit(InstInfoNdPort.default)
//   io.instInfoPassThroughPort.out := instInfoReg

//   // Pass to the next stage in a sequential way
//   val issuedInfoReg = RegInit(IssuedInfoNdPort.default)
//   io.issuedInfoPort := issuedInfoReg

//   // Start: state machine

//   /** State behaviors:
//     *   - Fallback: keep inst store reg, no issue, no fetch (pre)
//     *   - `nonBlocking`: fetch (pre)
//     *     - If can fetch: store inst to reg
//     *       - If blocking: (nothing else)
//     *       - if still non-blocking: issue
//     *     - If cannot fetch: (nothing else)
//     *   - `blocking`:
//     *     - If still blocking: (nothing else)
//     *     - If non-blocking: issue
//     *
//     * State behaviors:
//     *   - `nonBlocking`:
//     *     - If can fetch: decode from port
//     *     - If cannot fetch: decode nop
//     *   - `blocking`: decode from inst store reg
//     *
//     * State transitions:
//     *   - `nonBlocking`: is blocking -> `blocking`, else `nonBlocking`
//     *   - `blocking`: is blocking -> `blocking`, else `nonBlocking`
//     */
//   val nextState = WireInit(State.nonBlocking)
//   val stateReg  = RegInit(State.nonBlocking)
//   stateReg := nextState

//   // State machine output (including fallback)
//   val instDecodeStoreReg = RegInit(DecodeOutNdPort.default)
//   instDecodeStoreReg := instDecodeStoreReg
//   val selectedInstDecode = WireDefault(DecodeOutNdPort.default)
//   val isAllowIssue       = WireDefault(false.B)
//   val isFetch            = WireDefault(false.B)

//   // Connect state machine output to I/O
//   io.fetchInstDecodePort.ready := isFetch

//   // Implement output function
//   switch(stateReg) {
//     is(State.nonBlocking) {
//       isFetch := true.B
//       when(io.fetchInstDecodePort.valid) {
//         selectedInstDecode := io.fetchInstDecodePort.bits
//         instDecodeStoreReg := io.fetchInstDecodePort.bits
//         instInfoReg        := io.instInfoPassThroughPort.in
//         // 指令不存在异常
//         // instInfoReg.exceptionRecords(Csr.ExceptionIndex.ine) := !isInstValid
//       }
//     }
//     is(State.blocking) {
//       selectedInstDecode := instDecodeStoreReg
//       instInfoReg        := instInfoReg
//     }
//   }

//   // State machine input
//   /** Determine blocking:
//     *   - If stall signal: blocking
//     *   - If not stall:
//     *     - If inst valid:
//     *       - If no data hazard: non-blocking
//     *       - If data hazard: blocking
//     *     - If inst invalid:
//     *       - non-blocking
//     */

//   val isGprScoreboardBlocking = WireDefault(selectedInstDecode.info.gprReadPorts.map { port =>
//     port.en && io.regScores(port.addr)
//   }.reduce(_ || _))
//   val isCsrScoreboardBlocking = WireDefault(
//     selectedInstDecode.info.csrWriteEn &&
//       io.csrRegScores(selectedInstDecode.info.csrAddr)
//   )
//   val isScoreboardBlocking = WireDefault(isGprScoreboardBlocking && isCsrScoreboardBlocking)
//   val isBlocking = WireDefault(
//     io.pipelineControlPort.stall || isScoreboardBlocking
//   )

//   // Implement output function
//   switch(stateReg) {
//     is(State.nonBlocking) {
//       when(io.fetchInstDecodePort.valid && !isBlocking) {
//         isAllowIssue := true.B
//       }
//     }
//     is(State.blocking) {
//       when(!isBlocking) {
//         isAllowIssue := true.B
//       }
//     }
//   }

//   // Next state function
//   nextState := Mux(isBlocking, State.blocking, State.nonBlocking)

//   // End: state machine

//   // Fallback for no operation
//   issuedInfoReg.isValid              := isAllowIssue
//   issuedInfoReg.info                 := DontCare
//   issuedInfoReg.info.gprWritePort.en := false.B
//   io.occupyPorts.foreach { port =>
//     port.en   := false.B
//     port.addr := DontCare
//   }
//   io.csrOccupyPorts.foreach { port =>
//     port.en   := false.B
//     port.addr := DontCare
//   }

//   // Issue pre-execution instruction

//   when(isAllowIssue) {
//     // Indicate the occupation in scoreboard
//     instDecodeStoreReg := DecodeOutNdPort.default // Patch for preventing reissue an inst itself
//     io.occupyPorts.zip(Seq(selectedInstDecode.info.gprWritePort)).foreach {
//       case (occupyPort, accessInfo) =>
//         occupyPort.en   := accessInfo.en
//         occupyPort.addr := accessInfo.addr
//     }

//     io.csrOccupyPorts.zip(Seq(selectedInstDecode.info)).foreach {
//       case (csrOccupyPort, accessInfo) =>
//         csrOccupyPort.en   := accessInfo.csrWriteEn
//         csrOccupyPort.addr := accessInfo.csrAddr
//     }

//     issuedInfoReg.info            := selectedInstDecode.info
//     instInfoReg.csrWritePort.en   := selectedInstDecode.info.csrWriteEn
//     instInfoReg.csrWritePort.addr := selectedInstDecode.info.csrAddr
//   }

//   // clear
//   when(io.pipelineControlPort.clear) {
//     InstInfoNdPort.invalidate(instInfoReg)
//     // instInfoReg := InstInfoNdPort.default
//     issuedInfoReg := IssuedInfoNdPort.default
//     InstInfoNdPort.invalidate(instInfoReg)
//   }
//   // flush all regs
//   when(io.pipelineControlPort.flush) {
//     InstInfoNdPort.invalidate(instInfoReg)
//     // instInfoReg := InstInfoNdPort.default
//     issuedInfoReg      := IssuedInfoNdPort.default
//     stateReg           := State.nonBlocking
//     instDecodeStoreReg := DecodeOutNdPort.default
//     InstInfoNdPort.invalidate(instInfoReg)
//   }
// }
