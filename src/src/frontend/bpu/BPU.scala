package frontend.bpu

import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import frontend.bpu.components.Bundles.FtbEntryNdPort
import frontend.bpu.components.FTB
import frontend.bundles.{BpuFtqPort, FtqBlockPort, FtqBpuMetaPort}
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
    val bpuFtqPort = Flipped(new BpuFtqPort)
    // Predict
    val pc = Input(UInt(Width.Reg.data))

    // PC
    val mainRedirectValid = Output(Bool())
    val mainRedirectPc    = Output(UInt(Width.Reg.data))

    // PMU
    // TODO: use PMU to monitor miss-prediction rate and each component useful rate
  })
  // P1 signals
  val mainRedirectValid = WireDefault(false.B)

  // FTB fetch target buffer
  val ftbHit      = WireDefault(false.B)
  val ftbHitIndex = WireDefault(0.U(ftbNway.W))
  val ftbEntryReg = new FtbEntryNdPort

  // Tage
  val predictTaken = WireDefault(true.B)
  val predictValid = WireDefault(false.B)

  // RAS return address stack
  val rasTopAddr = WireDefault(0.U(addr.W))

  val flushDelay   = RegNext((io.backendFlush | (mainRedirectValid & ~ftqFullDelay)), false.B)
  val ftqFullDelay = RegNext(io.bpuFtqPort.ftqFull, false.B)

  ////////////////////////////////////////////////////////////////////////////////////
  // Query logic
  ////////////////////////////////////////////////////////////////////////////////////
  val tageMetaReg = new BpuFtqMetaPort
  val bpuMeta     = new BpuFtqMetaPort

  // P0
  // FTQ qutput generate
  val isCrossPage = WireDefault(false.B)
//  isCrossPage := io.pc(11, 0) > "b1111_1111_0000".U // if 4 instr already cross the page limit
  // one instr has 4B; one page addr length 12bits ; when cross page,address add fetchNum result in page's high bits from all 1 to 0
  isCrossPage := io.pc(11, 0) > Cat(-1.S((10 - log2Ceil(fetchNum)).W).asUInt, 0.U(Param.Width.ICache._fetchOffset.W))
  when(io.bpuFtqPort.ftqFull) {
    // todo default 0
    io.bpuFtqPort.ftqP0 := FtqBlockPort.default
  }.otherwise {
    // p0 generate a next-line prediction
    when(isCrossPage) {
      io.bpuFtqPort.ftqP0.length := (0.U(12.W) - io.pc(11, 0)) >> 2
    }.otherwise {
      io.bpuFtqPort.ftqP0.length := fetchNum.U(log2Ceil(fetchNum + 1).W)
    }
    io.bpuFtqPort.ftqP0.startPc := io.pc
    io.bpuFtqPort.ftqP0.valid   := true.B
    // If cross page, length will be cut,so ensures no cacheline cross
    io.bpuFtqPort.ftqP0.isCrossCacheline := (io.pc(Param.Width.ICache._fetchOffset - 1, 2) =/= 0.U(
      log2Ceil(fetchNum).W
    )) & ~isCrossPage
    io.bpuFtqPort.ftqP0.predictTaken := false.B
    io.bpuFtqPort.ftqP0.predictValid := false.B
  }

  // p1 (the next clock of p0)
  mainRedirectValid := ftbHit && !flushDelay && !ftqFullDelay
  val p1Pc = RegNext(io.pc, 0.U(addr.W))

  // p1 FTQ output
  when(mainRedirectValid) {
    // Main BPU generate a redirect in P1
    io.bpuFtqPort.ftqP1.valid := true.B
    io.bpuFtqPort.ftqP1.isCrossCacheline := ftbEntryReg.fallThroughAddress(Param.Width.ICache._fetchOffset, 2) -
      p1Pc(Param.Width.ICache._fetchOffset, 2) // Use 1 + log(fetchNum) bits minus to ensure no overflow
    io.bpuFtqPort.ftqP1.predictValid := true.B
    //  switch ftbEntryReg.BranchType
    switch(ftbEntryReg.branchType) {
      is(Param.BPU.BranchType.uncond) {
        io.bpuFtqPort.ftqP1.predictTaken := predictTaken
      }
      is(Param.BPU.BranchType.call, Param.BPU.BranchType.ret, Param.BPU.BranchType.uncond) {
        io.bpuFtqPort.ftqP1.predictTaken := true.B
      }
    }
  }.otherwise {
    // default 0
    io.bpuFtqPort.ftqP1 := FtqBlockPort.default
  }

  // debug
  val bpuPc         = RegInit(0.U(addr.W))
  val ftbBranchType = RegInit(0.U(Param.BPU.BranchType.width.W))
  bpuPc         := io.bpuFtqPort.ftqP0.startPc
  ftbBranchType := ftbEntryReg.branchType

  // Pc output
  io.mainRedirectValid               := mainRedirectValid
  io.bpuFtqPort.mainBpuRedirectValid := mainRedirectValid
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
  bpuMeta.ftbHit        := ftbHit
  bpuMeta.ftbHitIndex   := ftbHitIndex
  bpuMeta.valid         := ftbHit
  io.bpuFtqPort.ftqMeta := bpuMeta.asUInt | tageMetaReg.asUInt

  ////////////////////////////////////////////////////////////////////
  // Update Logic
  ////////////////////////////////////////////////////////////////////
  val misPredict        = WireDefault(io.bpuFtqPort.ftqTrainMeta.predictedTaken ^ io.bpuFtqPort.ftqTrainMeta.isTaken)
  val tageUpdateInfoReg = new TagePredictorUpdateInfoPort
  val ftbEntryUpdate    = new FtbEntryNdPort
  val rasPush = WireDefault(
    io.bpuFtqPort.ftqTrainMeta.valid & (io.bpuFtqPort.ftqTrainMeta.branchType === Param.BPU.BranchType.call)
  )
  val rasPop = WireDefault(
    io.bpuFtqPort.ftqTrainMeta.valid & (io.bpuFtqPort.ftqTrainMeta.branchType === Param.BPU.BranchType.ret)
  )
  val rasPushAddr = WireDefault(io.bpuFtqPort.ftqTrainMeta.fallThroughAddress)

  // Only following conditions will trigger a FTB update:
  // 1. This is a conditional branch
  // 2. First time a branch jumped
  // 3. A FTB pollution is detected
  val ftbUpdateValid = WireDefault(
    io.bpuFtqPort.ftqTrainMeta.valid && (misPredict && (!io.bpuFtqPort.ftqTrainMeta.ftbHit) || (io.bpuFtqPort.ftqTrainMeta.ftbDirty && io.bpuFtqPort.ftqTrainMeta.ftbHit))
  )

  // Direction preditor update policy
  tageUpdateInfoReg.valid          := io.bpuFtqPort.ftqTrainMeta.valid
  tageUpdateInfoReg.predictCorrect := io.bpuFtqPort.ftqTrainMeta.valid & ~misPredict
  tageUpdateInfoReg.isConditional  := io.bpuFtqPort.ftqTrainMeta.branchType === Param.BPU.BranchType.cond
  tageUpdateInfoReg.branchTaken    := io.bpuFtqPort.ftqTrainMeta.isTaken
  tageUpdateInfoReg.bpuMeta        := io.bpuFtqPort.ftqTrainMeta.bpuMeta

  ftbEntryUpdate.valid              := ~(io.bpuFtqPort.ftqTrainMeta.ftbDirty & io.bpuFtqPort.ftqTrainMeta.ftbHit)
  ftbEntryUpdate.tag                := io.bpuFtqPort.ftqTrainMeta.startPc(addr - 1, log2Ceil(ftbNset) + 2)
  ftbEntryUpdate.branchType         := io.bpuFtqPort.ftqTrainMeta.branchType
  ftbEntryUpdate.isCrossCacheline   := io.bpuFtqPort.ftqTrainMeta.isCrossCacheline
  ftbEntryUpdate.jumpTargetAddress  := io.bpuFtqPort.ftqTrainMeta.jumpTargetAddress
  ftbEntryUpdate.fallThroughAddress := io.bpuFtqPort.ftqTrainMeta.fallThroughAddress

  // connect fetch target buffer module
  // assign ftbHit = 0
  val ftbModule = Module(new FTB)
  // query
  ftbModule.io.queryPc := io.pc
  ftbEntryReg          := ftbModule.io.queryEntryPort
  ftbHit               := ftbModule.io.hit
  ftbHitIndex          := ftbModule.io.hitIndex
  // update
  ftbModule.io.updatePc        := io.bpuFtqPort.ftqTrainMeta.startPc
  ftbModule.io.updateWayIndex  := io.bpuFtqPort.ftqTrainMeta.ftbHitIndex
  ftbModule.io.updateValid     := ftbUpdateValid
  ftbModule.io.updateDirty     := (io.bpuFtqPort.ftqTrainMeta.ftbDirty && io.bpuFtqPort.ftqTrainMeta.ftbHit)
  ftbModule.io.updateEntryPort := ftbEntryUpdate

  // connect tage Predictor module
  val tagePredictorModule = Module(new TagePredictor)
  tagePredictorModule.io.pc                 := io.pc
  tageMetaReg                               := tagePredictorModule.io.bpuMetaPort
  predictTaken                              := tagePredictorModule.io.predictBranchTaken
  predictValid                              := tagePredictorModule.io.predictValid
  tagePredictorModule.io.updatePc           := io.bpuFtqPort.ftqTrainMeta.startPc
  tagePredictorModule.io.updateInfoPort     := tageUpdateInfoReg
  tagePredictorModule.io.perfTagHitCounters <> DontCare

  // connect return address stack module
  val rasModule = Module(new RAS)
  rasModule.io.push     := rasPush
  rasModule.io.callAddr := rasPushAddr
  rasModule.io.pop      := rasPop
  rasTopAddr            := rasModule.io.topAddr

}
