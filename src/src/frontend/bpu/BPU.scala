package frontend.bpu

import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import frontend.bpu.components.Bundles.FtbEntryPort
import frontend.bpu.components.FTB
import frontend.bundles.{FtqBlockPort, FtqBpuMetaPort}
import spec._

// BPU is the Branch Predicting Unit
// BPU does the following things:
// 1. accept update info from FTQ
// 2. provide update to tage predictor
// 3. send pc into tage predictor and generate FTQ block

class BPU(
  componentCtrWidth: Seq[Int] = Param.BPU.TagePredictor.componentCtrWidth,
  ftbNway:           Int      = Param.BPU.FTB.nway,
  ftbNset:           Int      = Param.BPU.FTB.nset,
  fetchNum:          Int      = Param.fetchInstMaxNum,
  addr:              Int      = spec.Width.Mem._addr)
    extends Module {
  val baseCtrWidth = componentCtrWidth(0)

  val io = IO(new Bundle {
    // Backend flush
    val backendFlush = Input(Bool())

    // FTQ
    // Predict
    val pc         = Input(UInt(Width.Reg.data))
    val ftqFull    = Input(Bool())
    val ftqP0      = Output(new FtqBlockPort)
    val ftqP1      = Output(new FtqBlockPort)
    val ftqMetaOut = Output(new BpuFtqMetaPort)

    // Train predicotr
    val ftqMeta = Input(new FtqBpuMetaPort)

    // PC
    val mainRedirectValid = Output(Bool())
    val mainRedirectPc    = Output(UInt(Width.Reg.data))

    // PMU
    // TODO: use PMU to monitor miss-prediction rate and each component useful rate
  })
  // P1 signals
  val p1Pc              = RegInit(0.U(addr.W))
  val mainRedirectValid = WireDefault(false.B)

  // FTB fetch target buffer
  val ftbHit      = WireDefault(false.B)
  val ftbHitIndex = WireDefault(0.U(ftbNway.W))
  val ftbEntryReg = new FtbEntryPort

  // Tage
  val predictTaken = WireDefault(true.B)
  val predictValid = WireDefault(false.B)

  // RAS return address stack
  val rasTopAddr = WireDefault(0.U(addr.W))

  val flushDelay   = RegInit(false.B)
  val ftqFullDelay = RegInit(false.B)
  flushDelay   := RegNext(io.backendFlush | (mainRedirectValid & ~ftqFullDelay))
  ftqFullDelay := RegNext(io.ftqFull)

  ////////////////////////////////////////////////////////////////////////////////////
  // Query logic
  ////////////////////////////////////////////////////////////////////////////////////
  val tageMetaReg = new BpuFtqMetaPort
  val bpuMeta     = new BpuFtqMetaPort

  // P0
  // FTQ qutput generate
  val isCrossPage = WireDefault(false.B)
  isCrossPage := io.pc(11, 0) > "b1111_1111_0000".U // if 4 instr already cross the page limit
  when(io.ftqFull) {
    // todo default 0
    io.ftqP0 := FtqBlockPort.default
  }.otherwise {
    // p0 generate a next-line prediction
    when(isCrossPage) {
      io.ftqP0.length := (0.U(12.W) - io.pc(11, 9)) >> 2
    }.otherwise {
      io.ftqP0.length := 4.U(log2Ceil(fetchNum + 1).W)
    }
    io.ftqP0.startPc := io.pc
    io.ftqP0.valid   := true.B
    // If cross page, length will be cut,so ensures no cacheline cross
    io.ftqP0.isCrossCacheline := (io.pc(3, 2) =/= 0.U(2.W)) & ~isCrossPage
    io.ftqP0.predictTaken     := false.B
    io.ftqP0.predictValid     := false.B
  }

  // p1
  mainRedirectValid := ftbHit && !flushDelay && !ftqFullDelay
  p1Pc              := RegNext(io.pc)

  // p1 FTQ output
  when(mainRedirectValid) {
    // Main BPU generate a redirect in P1
    io.ftqP1.valid := true.B
    io.ftqP1.isCrossCacheline := ftbEntryReg.fallThroughAddress(2 + log2Ceil(fetchNum), 2) -
      p1Pc(2 + log2Ceil(fetchNum), 2) // Use 3bit minus to ensure no overflow
    io.ftqP1.predictValid := true.B
    //  switch ftbEntryReg.BranchType
    switch(ftbEntryReg.branchType) {
      is(Param.BPU.BranchType.uncond) {
        io.ftqP1.predictTaken := predictTaken
      }
      is(Param.BPU.BranchType.call, Param.BPU.BranchType.ret, Param.BPU.BranchType.uncond) {
        io.ftqP1.predictTaken := true.B
      }
    }

  }.otherwise {
    // default 0
    io.ftqP1 := FtqBlockPort.default
  }

  // debug
  val bpuPc         = RegInit(0.U(addr.W))
  val ftbBranchType = RegInit(0.U(2.W))
  bpuPc         := io.ftqP0.startPc
  ftbBranchType := ftbEntryReg.branchType

  // Pc output
  io.mainRedirectValid := mainRedirectValid
  //  case branchType
  switch(ftbEntryReg.branchType) {
    is(Param.BPU.BranchType.cond) {
      io.mainRedirectPc := Mux(predictTaken, ftbEntryReg.jumpTargetAddress, ftbEntryReg.fallThroughAddress)
    }
    is(Param.BPU.BranchType.call, Param.BPU.BranchType.uncond) {
      io.mainRedirectPc := ftbEntryReg.jumpTargetAddress
    }
    is(Param.BPU.BranchType.ret) {
      io.mainRedirectPc := rasTopAddr
    }
  }

  // FTQ meta output
  bpuMeta.ftbHit      := ftbHit
  bpuMeta.ftbHitIndex := ftbHitIndex
  bpuMeta.valid       := ftbHit
  io.ftqMetaOut       := bpuMeta.asUInt | tageMetaReg.asUInt

  ////////////////////////////////////////////////////////////////////
  // Update Logic
  ////////////////////////////////////////////////////////////////////
  val misPredict        = WireDefault(false.B)
  val ftbUpdateValid    = WireDefault(false.B)
  val tageUpdateInfoReg = new TagePredictorUpdateInfoPort
  val ftbEntryUpdate    = new FtbEntryPort
  val rasPush           = WireDefault(false.B)
  val rasPop            = WireDefault(false.B)
  val rasPushAddr       = WireDefault(0.U(addr.W))
  rasPush     := io.ftqMeta.valid & (io.ftqMeta.branchType === Param.BPU.BranchType.call)
  rasPushAddr := io.ftqMeta.fallThroughAddress
  rasPop      := io.ftqMeta.valid & (io.ftqMeta.branchType === Param.BPU.BranchType.ret)
  misPredict  := io.ftqMeta.predictedTaken ^ io.ftqMeta.isTaken
  // Only following conditions will trigger a FTB update:
  // 1. This is a conditional branch
  // 2. First time a branch jumped
  // 3. A FTB pollution is detected
  ftbUpdateValid := io.ftqMeta.valid && (misPredict && (!io.ftqMeta.ftbHit) || (io.ftqMeta.ftbDirty && io.ftqMeta.ftbHit))

  // Direction preditor update policy
  tageUpdateInfoReg.valid          := io.ftqMeta.valid
  tageUpdateInfoReg.predictCorrect := io.ftqMeta.valid & ~misPredict
  tageUpdateInfoReg.isConditional  := io.ftqMeta.branchType === Param.BPU.BranchType.cond
  tageUpdateInfoReg.branchTaken    := io.ftqMeta.isTaken
  tageUpdateInfoReg.bpuMeta        := io.ftqMeta.bpuMeta

  ftbEntryUpdate.valid              := ~(io.ftqMeta.ftbDirty & io.ftqMeta.ftbHit)
  ftbEntryUpdate.tag                := io.ftqMeta.startPc(addr - 1, log2Ceil(ftbNset) + 2)
  ftbEntryUpdate.branchType         := io.ftqMeta.branchType
  ftbEntryUpdate.isCrossCacheline   := io.ftqMeta.isCrossCacheline
  ftbEntryUpdate.jumpTargetAddress  := io.ftqMeta.jumpTargetAddress
  ftbEntryUpdate.fallThroughAddress := io.ftqMeta.fallThroughAddress

  // connect fetch target buffer module
  // assign ftbHit = 0
  val ftbModule = Module(new FTB)
  // query
  ftbModule.io.queryPc        := io.pc
  ftbModule.io.queryEntryPort <> ftbEntryReg
  ftbHit                      := ftbModule.io.hit
  ftbHitIndex                 := ftbModule.io.hitIndex
  // update
  ftbModule.io.updatePc        := io.ftqMeta.startPc
  ftbModule.io.updateWayIndex  := io.ftqMeta.ftbHitIndex
  ftbModule.io.updateValid     := ftbUpdateValid
  ftbModule.io.updateDirty     := (io.ftqMeta.ftbDirty && io.ftqMeta.ftbHit)
  ftbModule.io.updateEntryPort <> ftbEntryUpdate

  // connect tage Predictor module
  val tagePredictorModule = Module(new TagePredictor)
  tagePredictorModule.io.pc                 := io.pc
  tageMetaReg                               := tagePredictorModule.io.bpuMetaPort
  predictTaken                              := tagePredictorModule.io.predictBranchTaken
  predictValid                              := tagePredictorModule.io.predictValid
  tagePredictorModule.io.updatePc           := io.ftqMeta.startPc
  tagePredictorModule.io.updateInfoPort     := tageUpdateInfoReg
  tagePredictorModule.io.perfTagHitCounters <> DontCare

  // connect return address stack module
  val rasModule = Module(new RAS)
  rasModule.io.push     := rasPush
  rasModule.io.callAddr := rasPushAddr
  rasModule.io.pop      := rasPop
  rasTopAddr            := rasModule.io.topAddr

}
