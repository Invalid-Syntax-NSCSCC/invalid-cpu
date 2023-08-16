package frontend.bpu

import chisel3._
import chisel3.util._
import frontend.bpu.bundles._
import frontend.bpu.components.Bundles.{FtbEntryNdPort, TageGhrInfo, TageMetaPort}
import frontend.bpu.components.FTB
import frontend.bundles.{BpuFtqPort, FtqBlockBundle}
import spec.Param.BPU.{BranchType, GhrFixType}
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

  val io = IO(new Bundle {
    // Backend flush
    val backendFlush   = Input(Bool())
    val preDecodeFlush = Input(Bool())
    val isFlushFromCu  = Input(Bool())

    // FTQ
    val bpuFtqPort = Flipped(new BpuFtqPort)

    // Predict
    val pc = Input(UInt(Width.Reg.data))

    // PC
    val bpuRedirectPc = Output(Valid(UInt(Width.Reg.data)))
    val fetchNum      = Output(UInt(log2Ceil(Param.fetchInstMaxNum + 1).W))

    // PMU
    // TODO: use PMU to monitor miss-prediction rate and each component useful rate
  })
  io.fetchNum := io.bpuFtqPort.ftqP0.fetchLastIdx +& 1.U
  // P1 signals
  val mainRedirectValid = WireDefault(false.B)

  // FTB fetch target buffer
  val ftbHit      = WireDefault(false.B)
  val ftbHitIndex = Wire(UInt(ftbNway.W))
  val ftbEntry    = Wire(new FtbEntryNdPort)

  // Tage
  val predictTaken = Wire(Bool())
  val predictValid = Wire(Bool())

  // RAS return address stack
  val rasTopAddr = WireDefault(0.U(addr.W))

  val ftqFullDelay = RegNext(io.bpuFtqPort.ftqFull, false.B)
  val flushDelay   = RegNext(io.backendFlush || io.preDecodeFlush || (mainRedirectValid && !ftqFullDelay), false.B)

  ////////////////////////////////////////////////////////////////////////////////////
  // Query logic
  ////////////////////////////////////////////////////////////////////////////////////
  val tageQueryMeta = Wire(new TageMetaPort)

  // P0
  // FTQ qutput generate
  val isCrossPage = WireDefault(false.B)
//  isCrossPage := io.pc(11, 0) > "b1111_1111_0000".U // if 4 instr already cross the page limit
  // one instr has 4B; one page addr length 12bits ; when cross page,address add fetchNum result in page's high bits from all 1 to 0
//  isCrossPage := io.pc(11, 0) > Cat(-1.S((10 - log2Ceil(fetchNum)).W).asUInt, 0.U(Param.Width.ICache._fetchOffset.W))
  // when not support crossCacheLine, isCrossPage must be false
  when(io.bpuFtqPort.ftqFull) {
    // todo default 0
    io.bpuFtqPort.ftqP0 := FtqBlockBundle.default
  }.otherwise {
    // p0 generate a next-line prediction
    when(isCrossPage) {
      io.bpuFtqPort.ftqP0.fetchLastIdx := (0.U(10.W) - io.pc(11, 2)) - 1.U
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
      }
      io.bpuFtqPort.ftqP0.fetchLastIdx := pcFetchNum - 1.U
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

  // p1 (the next clock of p0) get the query result
  mainRedirectValid := ftbHit && !flushDelay && !ftqFullDelay
  val p1Pc = RegNext(io.pc, 0.U(addr.W))

  // p1  FTQ output
  when(mainRedirectValid) {
    // Main BPU generate a redirect in P1
    io.bpuFtqPort.ftqP1.isValid          := true.B
    io.bpuFtqPort.ftqP1.isCrossCacheline := ftbEntry.isCrossCacheline
    io.bpuFtqPort.ftqP1.startPc          := p1Pc
    // calculate fetch num with notTakenFallThroughAddress - startPc
//    io.bpuFtqPort.ftqP1.length := (ftbEntry.fallThroughAddr(Param.Width.ICache._fetchOffset, 2) -
//      p1Pc(Param.Width.ICache._fetchOffset, 2)) // Use 1 + log(fetchNum) bits minus to ensure no overflow
    io.bpuFtqPort.ftqP1.fetchLastIdx := ftbEntry.fetchLastIdx
    io.bpuFtqPort.ftqP1.predictValid := true.B
    //  switch predictTaken with ftbEntry.BranchType
    io.bpuFtqPort.ftqP1.predictTaken := true.B // (BranchType.call,BranchType.unCond)
    when(ftbEntry.branchType === BranchType.cond) {
      io.bpuFtqPort.ftqP1.predictTaken := predictTaken
    }
  }.otherwise {
    // default 0
    io.bpuFtqPort.ftqP1         <> DontCare
    io.bpuFtqPort.ftqP1.isValid := false.B
//    io.bpuFtqPort.ftqP1 := FtqBlockBundle.default
  }

  // Pc output
  io.bpuRedirectPc.valid         := mainRedirectValid
  io.bpuFtqPort.bpuRedirectValid := mainRedirectValid
  io.bpuRedirectPc.bits          := ftbEntry.jumpPartialTargetAddr << 2
  //  case branchType
  switch(ftbEntry.branchType) {
    is(Param.BPU.BranchType.cond) {
//      io.bpuRedirectPc.bits := Mux(predictTaken, ftbEntry.jumpTargetAddr, ftbEntry.fallThroughAddr)
      io.bpuRedirectPc.bits := Mux(
        predictTaken,
        ftbEntry.jumpPartialTargetAddr << 2,
        p1Pc + ((ftbEntry.fetchLastIdx +& 1.U) << 2)
      )
    }
    is(Param.BPU.BranchType.call, Param.BPU.BranchType.uncond) {
      io.bpuRedirectPc.bits := ftbEntry.jumpPartialTargetAddr << 2
    }
    is(Param.BPU.BranchType.ret) {
      // return inst is predict in preDecode Stage;
      // when preDecode predict error,use ftb to predict
    }
  }

  // FTQ meta output
  io.bpuFtqPort.bpuQueryMeta.tageQueryMeta := tageQueryMeta
  io.bpuFtqPort.bpuQueryMeta.ftbHit        := ftbHit
  io.bpuFtqPort.bpuQueryMeta.ftbHitIndex   := ftbHitIndex

  ////////////////////////////////////////////////////////////////////
  // Update Logic
  ////////////////////////////////////////////////////////////////////
  val directionPredictError = WireDefault(
    io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.predictedTaken ^ io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.isTaken
  )
  val tageUpdateInfo = Wire(new TagePredictorUpdateInfoPort)
  val ftbUpdateEntry = Wire(new FtbEntryNdPort)

  // Only following conditions will trigger a FTB update:
  // 1. This is a conditional branch or the first time of direct Immediately Jump
  // 2. First time a branch jumped
  // 3. A FTB pollution is detected
  //     dirty case 1: target error || fallThroughAddr error;
  //                2:At the same addr, original branch inst become nonBranch inst  after  cacop or change program
  val ftbUpdateValid = if (Param.isFtbUpdateRet) {
    WireDefault(
      io.bpuFtqPort.ftqBpuTrainMeta.valid &&
        (((directionPredictError || io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.branchType === BranchType.call || io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.branchType === BranchType.uncond) && (!io.bpuFtqPort.ftqBpuTrainMeta.ftbHit))
          || io.bpuFtqPort.ftqBpuTrainMeta.ftbDirty)
    )
    // all kinds of target error can update
  } else {
    WireDefault(
      io.bpuFtqPort.ftqBpuTrainMeta.valid &&
        (((directionPredictError || io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.branchType === BranchType.call || io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.branchType === BranchType.uncond) && (!io.bpuFtqPort.ftqBpuTrainMeta.ftbHit)) ||
          (io.bpuFtqPort.ftqBpuTrainMeta.ftbDirty && io.bpuFtqPort.ftqBpuTrainMeta.ftbHit))
    )
    // case 1: the first time of a branch inst taken ( include conditon branch, indirect jump that hasn't been predicted taken),
    // case 2: hit and dirty
    // in our design ret would not be update because ret is predicted in RAS in predecodeStage; which results in ftb dirty and not hit
  }

  // Tage predictor and FTB update policy
  tageUpdateInfo.valid          := io.bpuFtqPort.ftqBpuTrainMeta.valid
  tageUpdateInfo.predictCorrect := !directionPredictError
  tageUpdateInfo.isConditional := io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.branchType === Param.BPU.BranchType.cond
  tageUpdateInfo.branchTaken   := io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.isTaken
  tageUpdateInfo.tageOriginMeta := io.bpuFtqPort.ftqBpuTrainMeta.tageOriginMeta

  ftbUpdateEntry.valid      := !(io.bpuFtqPort.ftqBpuTrainMeta.ftbDirty && io.bpuFtqPort.ftqBpuTrainMeta.ftbHit)
  ftbUpdateEntry.tag        := io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.startPc(addr - 1, log2Ceil(ftbNset) + 2)
  ftbUpdateEntry.branchType := io.bpuFtqPort.ftqBpuTrainMeta.branchTakenMeta.branchType
  ftbUpdateEntry.isCrossCacheline      := io.bpuFtqPort.ftqBpuTrainMeta.isCrossCacheline
  ftbUpdateEntry.jumpPartialTargetAddr := io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.jumpPartialTargetAddr
  ftbUpdateEntry.fetchLastIdx          := io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.fetchLastIdx

  // global branch history update logic
  val ghrUpdateSignalBundle = WireDefault(
    io.bpuFtqPort.ftqBpuTrainMeta.ghrUpdateSignalBundle
  )
  // val ghrFixBundleReg = RegInit(GhrFixNdBundle.default)
  val ghrFixBundle = Wire(new GhrFixNdBundle)
  ghrFixBundle.isFixGhrValid := ghrUpdateSignalBundle.exeFixBundle.isExeFixValid || io.backendFlush || io.preDecodeFlush
  ghrFixBundle.isFixBranchTaken := Mux(
    ghrUpdateSignalBundle.exeFixBundle.isExeFixValid,
    ghrUpdateSignalBundle.exeFixBundle.exeFixIsTaken,
    ghrUpdateSignalBundle.isPredecoderBranchTaken
  )
  ghrFixBundle.ghrFixType := Mux(
    io.backendFlush && io.isFlushFromCu,
    GhrFixType.commitRecover,
    Mux(
      ghrUpdateSignalBundle.exeFixBundle.isExeFixValid,
      GhrFixType.exeFixJumpError,
      Mux(ghrUpdateSignalBundle.isPredecoderBranchTaken, GhrFixType.decodeUpdateJump, GhrFixType.decodeRecoder)
    )
  )
//  val tageGhrInfo = Reg(new TageGhrInfo())
//  tageGhrInfo := DontCare
//  tageGhrInfo := io.bpuFtqPort.ftqBpuTrainMeta.tageGhrInfo

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
  ftbModule.io.updatePc        := io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.startPc
  ftbModule.io.updateWayIndex  := io.bpuFtqPort.ftqBpuTrainMeta.ftbHitIndex
  ftbModule.io.updateValid     := ftbUpdateValid
  ftbModule.io.updateDirty     := (io.bpuFtqPort.ftqBpuTrainMeta.ftbDirty && io.bpuFtqPort.ftqBpuTrainMeta.ftbHit)
  ftbModule.io.updateEntryPort := ftbUpdateEntry

  // connect tage Predictor module
  val tagePredictorModule = Module(new TagePredictor)
  tagePredictorModule.io.pc             := io.pc
  tageQueryMeta                         := tagePredictorModule.io.tageQueryMeta
  predictTaken                          := tagePredictorModule.io.predictBranchTaken
  predictValid                          := tagePredictorModule.io.predictValid
  tagePredictorModule.io.updatePc       := io.bpuFtqPort.ftqBpuTrainMeta.branchAddrBundle.startPc
  tagePredictorModule.io.updateInfoPort := tageUpdateInfo
  // update global history in the next cycle
  tagePredictorModule.io.ghrUpdateNdBundle.bpuSpecTaken := io.bpuFtqPort.ftqP1.predictTaken
  // bpu predict info
  tagePredictorModule.io.ghrUpdateNdBundle.bpuSpecValid := mainRedirectValid
  tagePredictorModule.io.ghrUpdateNdBundle.fixBundle    := ghrFixBundle
  tagePredictorModule.io.ghrUpdateNdBundle.tageGhrInfo  := io.bpuFtqPort.ftqBpuTrainMeta.tageGhrInfo
//  // update global history in the next cycle
//  tagePredictorModule.io.ghrUpdateNdBundle.bpuSpecTaken := RegNext(
//    io.bpuFtqPort.ftqP1.predictTaken,
//    false.B
//  ) // bpu predict info
//  tagePredictorModule.io.ghrUpdateNdBundle.bpuSpecValid := RegNext(mainRedirectValid, false.B)
//  tagePredictorModule.io.ghrUpdateNdBundle.fixBundle    := ghrFixBundleReg
//  tagePredictorModule.io.ghrUpdateNdBundle.tageGhrInfo  := tageGhrInfo
//  tagePredictorModule.io.perfTagHitCounters <> DontCare

}
