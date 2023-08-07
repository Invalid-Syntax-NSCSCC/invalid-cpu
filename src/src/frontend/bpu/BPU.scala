package frontend.bpu

import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import frontend.bpu.components.Bundles.FtbEntryNdPort
import frontend.bpu.components.FTB
import frontend.bundles.{BpuFtqPort, FtqBlockBundle}
import spec.Param.BPU.BranchType
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
//  assert(Param.BPU.TagePredictor.componentCtrWidth != null)
//  val componentCtrWidth: Seq[Int] = Param.BPU.TagePredictor.componentCtrWidth
  val baseCtrWidth = componentCtrWidth(0)

  val io = IO(new Bundle {
    // Backend flush
    val backendFlush = Input(Bool())

    // FTQ
    val bpuFtqPort = Flipped(new BpuFtqPort)

    // Predict
    val pc = Input(UInt(Width.Reg.data))

    // PC
    val mainRedirectPc = Output(Valid(UInt(Width.Reg.data)))
    val fetchNum       = Output(UInt(log2Ceil(Param.fetchInstMaxNum + 1).W))

    // PMU
    // TODO: use PMU to monitor miss-prediction rate and each component useful rate
  })
  io.fetchNum := io.bpuFtqPort.ftqP0.length
  // P1 signals
  val mainRedirectValid = WireDefault(false.B)

  // FTB fetch target buffer
  val ftbHit      = WireDefault(false.B)
  val ftbHitIndex = WireDefault(0.U(ftbNway.W))
  val ftbEntry    = Wire(new FtbEntryNdPort)

  // Tage
  val predictTaken = WireDefault(true.B)
  val predictValid = WireDefault(false.B)

  // RAS return address stack
  val rasTopAddr = WireDefault(0.U(addr.W))

  val ftqFullDelay = RegNext(io.bpuFtqPort.ftqFull, false.B)
  val flushDelay   = RegNext((io.backendFlush || (mainRedirectValid && !ftqFullDelay)), false.B)

  ////////////////////////////////////////////////////////////////////////////////////
  // Query logic
  ////////////////////////////////////////////////////////////////////////////////////
  val tageMeta = WireDefault(BpuFtqMetaNdPort.default)
  val bpuMeta  = WireDefault(BpuFtqMetaNdPort.default)

  // P0
  // FTQ qutput generate
  val isCrossPage = WireDefault(false.B)
//  isCrossPage := io.pc(11, 0) > "b1111_1111_0000".U // if 4 instr already cross the page limit
  // one instr has 4B; one page addr length 12bits ; when cross page,address add fetchNum result in page's high bits from all 1 to 0
  isCrossPage := io.pc(11, 0) > Cat(-1.S((10 - log2Ceil(fetchNum)).W).asUInt, 0.U(Param.Width.ICache._fetchOffset.W))
  when(io.bpuFtqPort.ftqFull) {
    // todo default 0
    io.bpuFtqPort.ftqP0 := FtqBlockBundle.default
  }.otherwise {
    // p0 generate a next-line prediction
    when(isCrossPage) {
      io.bpuFtqPort.ftqP0.length := (0.U(10.W) - io.pc(11, 2))
    }.otherwise {
      // sequential pc
      val pcFetchNum = WireDefault(Param.fetchInstMaxNum.U(log2Ceil(Param.fetchInstMaxNum + 1).W))
      if (Param.fetchInstMaxNum != 1) {
        // TODO support fix fetch num
        when(
          io.pc(
            Param.Width.ICache._byteOffset - 1,
            Param.Width.ICache._instOffset
          ) + Param.fetchInstMaxNum.U < Param.fetchInstMaxNum.U
        ) {
          pcFetchNum := Param.fetchInstMaxNum.U - io.pc(
            Param.Width.ICache._fetchOffset - 1,
            Param.Width.ICache._instOffset
          )
        }
        when(io.pc(Param.Width.ICache._fetchOffset, Param.Width.ICache._instOffset) =/= 0.U) {}
      }
      io.bpuFtqPort.ftqP0.length := pcFetchNum
    }
    io.bpuFtqPort.ftqP0.startPc := io.pc
    io.bpuFtqPort.ftqP0.isValid := true.B
    // If cross page, length will be cut,so ensures no cacheline cross
    io.bpuFtqPort.ftqP0.isCrossCacheline := false.B // TODO support crossCacheline
//    io.bpuFtqPort.ftqP0.isCrossCacheline := (io.pc(Param.Width.ICache._fetchOffset - 1, 2) =/= 0.U(
//      log2Ceil(fetchNum).W
//    )) & ~isCrossPage
    io.bpuFtqPort.ftqP0.predictTaken := false.B
    io.bpuFtqPort.ftqP0.predictValid := false.B
  }

  // p1 (the next clock of p0)
  mainRedirectValid := ftbHit && !flushDelay && !ftqFullDelay
  val p1Pc = RegNext(io.pc, 0.U(addr.W))

  // p1git  FTQ output
  when(mainRedirectValid) {
    // Main BPU generate a redirect in P1
    io.bpuFtqPort.ftqP1.isValid          := true.B
    io.bpuFtqPort.ftqP1.isCrossCacheline := ftbEntry.isCrossCacheline
    io.bpuFtqPort.ftqP1.startPc          := p1Pc
    // when a fetch block has branch inst,cut fetch num
    io.bpuFtqPort.ftqP1.length := (ftbEntry.fallThroughAddress(Param.Width.ICache._fetchOffset, 2) -
      p1Pc(Param.Width.ICache._fetchOffset, 2)) // Use 1 + log(fetchNum) bits minus to ensure no overflow
    io.bpuFtqPort.ftqP1.predictValid := true.B
    //  switch ftbEntry.BranchType
    io.bpuFtqPort.ftqP1.predictTaken := true.B // (Param.BPU.BranchType.call, Param.BPU.BranchType.ret, Param.BPU.BranchType.cond)
    when(ftbEntry.branchType === Param.BPU.BranchType.cond) {
      io.bpuFtqPort.ftqP1.predictTaken := predictTaken
    }
  }.otherwise {
    // default 0
    io.bpuFtqPort.ftqP1 := FtqBlockBundle.default
  }

  // debug
  val bpuPc         = RegInit(0.U(addr.W))
  val ftbBranchType = RegInit(0.U(Param.BPU.BranchType.width.W))
  bpuPc         := io.bpuFtqPort.ftqP0.startPc
  ftbBranchType := ftbEntry.branchType

  // Pc output
  io.mainRedirectPc.valid            := mainRedirectValid
  io.bpuFtqPort.mainBpuRedirectValid := mainRedirectValid
  io.mainRedirectPc.bits             := ftbEntry.jumpTargetAddress
  //  case branchType
  switch(ftbEntry.branchType) {
    is(Param.BPU.BranchType.cond) {
      io.mainRedirectPc.bits := Mux(predictTaken, ftbEntry.jumpTargetAddress, ftbEntry.fallThroughAddress)
    }
    is(Param.BPU.BranchType.call, Param.BPU.BranchType.uncond) {
      io.mainRedirectPc.bits := ftbEntry.jumpTargetAddress
    }
    is(Param.BPU.BranchType.ret) {}
  }

  // FTQ meta output
  bpuMeta.ftbHit        := ftbHit
  bpuMeta.ftbHitIndex   := ftbHitIndex
  bpuMeta.valid         := ftbHit
  io.bpuFtqPort.ftqMeta := (bpuMeta.asUInt | tageMeta.asUInt).asTypeOf(new BpuFtqMetaNdPort)

  ////////////////////////////////////////////////////////////////////
  // Update Logic
  ////////////////////////////////////////////////////////////////////
  val directionPredictError = WireDefault(
    io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.predictedTaken ^ io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.isTaken
  )
  val tageUpdateInfo = Wire(new TagePredictorUpdateInfoPort)
  val ftbUpdateEntry = Wire(new FtbEntryNdPort)

  // Only following conditions will trigger a FTB update:
  // 1. This is a conditional branch or the first time of direct Immediately Jump
  // 2. First time a branch jumped
  // 3. A FTB pollution is detected
  //     dirty case 1: target error || fallThroughAddr error;
  //                2:At the same addr, original branch inst become nonBranch inst  after  cacop or change program

  val ftbUpdateValid = if (Param.isFTBupdateRet) {
    WireDefault(
      io.bpuFtqPort.ftqTrainMeta.valid &&
        (((directionPredictError || io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.branchType === BranchType.call || io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.branchType === BranchType.uncond) && (!io.bpuFtqPort.ftqTrainMeta.ftbHit))
          || io.bpuFtqPort.ftqTrainMeta.ftbDirty)
    )
    // all kinds of target error can update
  } else {
    WireDefault(
      io.bpuFtqPort.ftqTrainMeta.valid &&
        (((directionPredictError || io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.branchType === BranchType.call || io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.branchType === BranchType.uncond) && (!io.bpuFtqPort.ftqTrainMeta.ftbHit)) ||
          (io.bpuFtqPort.ftqTrainMeta.ftbDirty && io.bpuFtqPort.ftqTrainMeta.ftbHit))
    )
    // case 1: the first time of a branch inst taken ( include conditon branch, indirect jump that hasn't been predicted taken),
    // case 2: hit and dirty
    // in our design ret would not be update because ret is predicted in RAS in predecodeStage; which results in ftb dirty and not hit
  }

  // Direction preditor update policy
  tageUpdateInfo.valid          := io.bpuFtqPort.ftqTrainMeta.valid
  tageUpdateInfo.predictCorrect := !directionPredictError
  tageUpdateInfo.isConditional  := io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.branchType === Param.BPU.BranchType.cond
  tageUpdateInfo.branchTaken    := io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.isTaken
  tageUpdateInfo.bpuMeta        := io.bpuFtqPort.ftqTrainMeta.tageMeta

  ftbUpdateEntry.valid              := !(io.bpuFtqPort.ftqTrainMeta.ftbDirty && io.bpuFtqPort.ftqTrainMeta.ftbHit)
  ftbUpdateEntry.tag                := io.bpuFtqPort.ftqTrainMeta.branchAddrBundle.startPc(addr - 1, log2Ceil(ftbNset) + 2)
  ftbUpdateEntry.branchType         := io.bpuFtqPort.ftqTrainMeta.branchTakenMeta.branchType
  ftbUpdateEntry.isCrossCacheline   := io.bpuFtqPort.ftqTrainMeta.isCrossCacheline
  ftbUpdateEntry.jumpTargetAddress  := io.bpuFtqPort.ftqTrainMeta.branchAddrBundle.jumpTargetAddress
  ftbUpdateEntry.fallThroughAddress := io.bpuFtqPort.ftqTrainMeta.branchAddrBundle.fallThroughAddress

  // connect fetch target buffer module
  // assign ftbHit = 0
  val ftbModule = Module(new FTB)
  // query
  ftbModule.io.queryPc := io.pc
  ftbEntry             := ftbModule.io.queryEntryPort
  ftbHit               := ftbModule.io.hit
  ftbHitIndex          := ftbModule.io.hitIndex
  if (!Param.isBranchPredict) {
    ftbHit := false.B
  }
  // update
  ftbModule.io.updatePc        := io.bpuFtqPort.ftqTrainMeta.branchAddrBundle.startPc
  ftbModule.io.updateWayIndex  := io.bpuFtqPort.ftqTrainMeta.ftbHitIndex
  ftbModule.io.updateValid     := ftbUpdateValid
  ftbModule.io.updateDirty     := (io.bpuFtqPort.ftqTrainMeta.ftbDirty && io.bpuFtqPort.ftqTrainMeta.ftbHit)
  ftbModule.io.updateEntryPort := ftbUpdateEntry

  // connect tage Predictor module
  val tagePredictorModule = Module(new TagePredictor)
  tagePredictorModule.io.pc             := io.pc
  tageMeta                              := tagePredictorModule.io.bpuMetaPort
  predictTaken                          := tagePredictorModule.io.predictBranchTaken
  predictValid                          := tagePredictorModule.io.predictValid
  tagePredictorModule.io.updatePc       := io.bpuFtqPort.ftqTrainMeta.branchAddrBundle.startPc
  tagePredictorModule.io.updateInfoPort := tageUpdateInfo
//  tagePredictorModule.io.perfTagHitCounters <> DontCare

}
