package frontend.bpu

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import frontend.bpu.bundles.{BpuFtqMetaPort, TagePredictorUpdateInfoPort}
import frontend.bpu.components.Bundles.TageMetaPort
import spec._
import frontend.bpu.components._
import frontend.bpu.utils.Lfsr

// TAGE predictor
// This is the main predictor
class TagePredictor(
  tagComponentNum:      Int      = Param.BPU.TagePredictor.tagComponentTagWidth,
  tagComponentTagWidth: Int      = Param.BPU.TagePredictor.tagComponentTagWidth,
  ghrDepth:             Int      = Param.BPU.TagePredictor.ghrLength,
  historyLengths:       Seq[Int] = Param.BPU.TagePredictor.componentHistoryLength,
  phtDepths:            Seq[Int] = Param.BPU.TagePredictor.componentTableDepth,
  componentCtrWidth:    Seq[Int] = Param.BPU.TagePredictor.componentCtrWidth,
  componentUsefulWidth: Seq[Int] = Param.BPU.TagePredictor.componentUsefulWidth,
  entryNum:             Int      = Param.BPU.RAS.entryNum,
  addr:                 Int      = spec.Width.Mem._addr)
    extends Module {
  val addrWidth    = log2Ceil(addr)
  val pointerWidth = log2Ceil(entryNum)
  val io = IO(new Bundle {
    // Query signal
    val pc                 = Input(UInt(Width.Reg.data))
    val bpuMetaPort        = Output(new BpuFtqMetaPort)
    val predictBranchTaken = Output(Bool())
    val predictValid       = Output(Bool())

    // Update signals
    val updatePc       = Input(UInt(Width.Reg.data))
    val updateInfoPort = Input(new TagePredictorUpdateInfoPort)

    // TODO PMU
//    val perfTagHitCounters = Output(Vec(32, UInt((tagComponentNum + 1).W)))

  })

  // vec asign func
//  def vecAsign(veca:Vec(bool: Bool), vecb: Vec(Bool)): Unit = {
//    veca.zip(vecb).foreach(case(a,b)=>
//      a := b
//    )
//  }

  def vecAssign[T <: Data](dst: Vec[T], src: Vec[T]): Unit = {
    dst.zip(src).foreach {
      case (a, b) =>
        a := b
    }
  }
  // Input
  val updateMetaBundle = WireDefault(io.updateInfoPort.bpuMeta)

  // Signals
  // Query
  val takens = RegInit(VecInit(Seq.fill(tagComponentNum + 1)(false.B)))

  // Base predictor
  val baseTaken = RegInit(true.B)
  val baseCtr   = RegInit(0.U(componentCtrWidth(0).W)) // initial state : weakly taken

  // Tagged predictor
  // The provider id of the accepted prediction, selected using priority encoder
  val predPredictionId = RegInit(0.U((tagComponentNum + 1).W))
  // The provider id of the last hit provider
  val altPredPredctionId = RegInit(0.U((tagComponentNum + 1).W))
  // For example, provider 2,4 hit, and provider 1,3 missed
  // then pred is 4, and altpred is 2
  val tagTaken          = RegInit(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagHit            = RegInit(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val queryIsUseful     = RegInit(false.B) // Indicates whether the pred component is useful
  val queryNewEntryFlag = RegInit(false.B) // Indicates the provider is new

  // Meta
  val tagCtrs       = VecInit(Seq.fill(tagComponentNum)(0.U(3.W)))
  val tagUsefuls    = VecInit(Seq.fill(tagComponentNum)(0.U(3.W)))
  val tagQueryTags  = VecInit(Seq.fill(tagComponentNum)(0.U(tagComponentTagWidth.W)))
  val tagOriginTags = VecInit(Seq.fill(tagComponentNum)(0.U(tagComponentTagWidth.W)))
  val tagHitIndexs  = VecInit(Seq.fill(tagComponentNum)(0.U(10.W)))

  // update
  val updatePc              = RegInit(0.U(Width.Reg.data))
  val baseUpdateCtr         = RegInit(false.B)
  val updateValid           = RegInit(false.B)
  val globalHistoryUpdate   = RegInit(0.U(tagComponentNum.W))
  val updatePredictCorrect  = RegInit(0.U(tagComponentNum.W))
  val updateBranchTaken     = RegInit(0.U(tagComponentNum.W))
  val updateIsConditional   = RegInit(0.U(tagComponentNum.W))
  val updateNewEntryFlag    = RegInit(false.B) // Indicates the provider is new
  val updateProviderId      = RegInit(0.U(log2Ceil(tagComponentNum + 1).W))
  val updateALtProviderId   = RegInit(0.U(log2Ceil(tagComponentNum + 1).W))
  val updateCtr             = WireDefault(VecInit(Seq.fill(tagComponentNum + 1)(false.B)))
  val tagUpdateCtr          = RegInit(0.U(tagComponentNum.W))
  val tagUpdateUseful       = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagUpdateIncUseful    = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagUpdateReallocEntry = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagUpdateQueryUsefuls = VecInit(Seq.fill(tagComponentNum)(0.U(3.W)))
  val tagUpdateNewTags      = WireInit(VecInit(Seq.fill(tagComponentNum)(0.U(tagComponentTagWidth.W))))

  // Indicates the longest history component which useful is 0
  val tagUpdateUsefulZeroId = RegInit(0.U(log2Ceil(tagComponentNum + 1).W))

  // pingpong counter & lfsr
  // is a random number array
  val randomR                        = LFSR(width = 16)
  val tagUpdateUsefulPingpongCounter = RegInit(0.U(tagComponentNum.W))

  // USE_ALT_ON_NA counter
  val useAltOnNaCounterTables = RegInit(VecInit(Seq.fill(8)(0.U(4.W))))
  val useALtOnNaCounter       = RegInit(0.U(4.W))
  val useAlt                  = RegInit(false.B)

  //  integer use_alt_cnt;

  ////////////////////////////////////////////////////////////////////////////////////////////
  // END of Defines
  ////////////////////////////////////////////////////////////////////////////////////////////

  // Global History Register
  val ghr = RegInit(0.U(ghrDepth.W))
  when(updateValid) {
    ghr := Cat(ghr(ghrDepth - 2, 0), updateBranchTaken)
  }
  globalHistoryUpdate := updateValid

  ////////////////////////////////////////////////////////////////////////////////////////////
  // Query Logic
  ////////////////////////////////////////////////////////////////////////////////////////////

  // Base Predictor
  val basePredictor = Module(new BasePredictor())
  basePredictor.io.pc          := io.pc
  basePredictor.io.updateValid := updateValid
  baseTaken                    := basePredictor.io.isTaken
  baseUpdateCtr                := basePredictor.io.ctr
  basePredictor.io.updatePc    := updatePc
  basePredictor.io.isCtrInc    := updateBranchTaken
  basePredictor.io.updateCtr   := updateMetaBundle.providerCtrBits(0)
  // basePredictor.io.updateCtr   <> io.updateInfoPort.bpuMeta.providerCtrBits(0)

  // Tagged Predictor Generate
  val taggedPreditors = Seq.range(0, tagComponentNum).map { providerId =>
    {
      val taggedPreditor = Module(
        new TaggedPreditor(
          ghrLength      = historyLengths(providerId + 1),
          phtDepth       = phtDepths(providerId + 1),
          phtUsefulWidth = componentUsefulWidth(providerId + 1),
          phtCtrWidth    = componentCtrWidth(providerId + 1)
        )
      )
      // Query
      taggedPreditor.io.isGlobalHistoryUpdate := updateValid
      taggedPreditor.io.globalHistory         := ghr(historyLengths(providerId + 1) + 1)
      taggedPreditor.io.pc                    := io.pc
      tagUsefuls(providerId)                  := taggedPreditor.io.usefulBits
      tagCtrs(providerId)                     := taggedPreditor.io.ctrBits
      tagQueryTags(providerId)                := taggedPreditor.io.queryTag
      tagOriginTags(providerId)               := taggedPreditor.io.hitIndex
      tagTaken(providerId)                    := taggedPreditor.io.taken
      tagHit(providerId)                      := taggedPreditor.io.tagHit

      // update
      taggedPreditor.io.updatePc         := io.updatePc
      taggedPreditor.io.updateValid      := (updateValid & updateIsConditional)
      taggedPreditor.io.incUseful        := tagUpdateIncUseful(providerId)
      taggedPreditor.io.updateUseful     := tagUpdateUseful(providerId)
      taggedPreditor.io.updateUsefulBits := updateMetaBundle.tagPredictorUsefulBits(providerId)
      taggedPreditor.io.updateCtr        := tagUpdateCtr.asBools(providerId)
      taggedPreditor.io.incCtr           := updateBranchTaken.asBools(providerId)
      taggedPreditor.io.updateCtrBits    := updateMetaBundle.providerCtrBits(providerId)
      taggedPreditor.io.reallocEntry     := tagUpdateReallocEntry(providerId)
      taggedPreditor.io.updateTag        := tagUpdateNewTags(providerId)
      taggedPreditor.io.updateIndex      := updateMetaBundle.tagPredictorHitIndex(providerId)

      taggedPreditor
    }
  }

  queryIsUseful := (takens(predPredictionId) =/= takens(altPredPredctionId))

  // Select the longest match provider
  // predPredictionId = 0.U((tagComponentNum + 1).W)
  Seq
    .range(0, tagComponentNum)
    .foreach(i =>
      when(tagHit(i)) {
        predPredictionId := (i + 1).U((tagComponentNum + 1).W)
      }
    )

  // Select altpred
  val altpredPool = WireDefault(VecInit(Seq.fill(tagComponentNum + 1)(false.B)))
  //  altpredPool    := Cat(tagHit, 1.U(1.W))
  Seq.range(1, tagComponentNum + 1).foreach(i => altpredPool(i) := tagHit(i - 1))
  altpredPool(0) := true.B
  when(predPredictionId =/= 0.U) {
    altpredPool(predPredictionId) := 0.U(1.W)
  }

  // altPredPredctionId = 0.U((tagComponentNum + 1).W)
  Seq
    .range(0, tagComponentNum + 1)
    .foreach(i =>
      when(altpredPool(i)) {
        altPredPredctionId := i.U((tagComponentNum + 1).W)
      }
    )

  // Output logic
  io.predictValid := true.B
//  takens          := Cat(tagTaken, baseTaken)
  Seq.range(1, tagComponentNum + 1).foreach { i =>
    takens(i) := tagTaken(i - 1)
  }
  takens(0) := baseTaken
  queryNewEntryFlag := ((tagCtrs(predPredictionId - 1.U(((tagComponentNum + 1).W))) === 3.U(3.W) ||
    tagCtrs(predPredictionId - 1.U(((tagComponentNum + 1).W))) === 4.U(3.W)) &&
    predPredictionId =/= 0.U(1.W))
  useALtOnNaCounter := useAltOnNaCounterTables(io.pc(4, 2))
  useAlt            := (useALtOnNaCounter(3) === 1.U(1.W)) && queryNewEntryFlag

  // assign useAlt = 0
  io.predictBranchTaken := takens(predPredictionId)

  // Meta
  val queryMetaBundle = WireDefault(TageMetaPort.default)
  vecAssign(queryMetaBundle.tagPredictorUsefulBits, tagUsefuls)
  vecAssign(queryMetaBundle.tagPredictorHitIndex, tagHitIndexs)
  vecAssign(queryMetaBundle.tagPredictorQueryTag, tagQueryTags)
  vecAssign(queryMetaBundle.tagPredictorOriginTag, tagOriginTags)
  queryMetaBundle.useful             := queryIsUseful
  queryMetaBundle.providerId         := predPredictionId
  queryMetaBundle.altProviderId      := altPredPredctionId
  queryMetaBundle.providerCtrBits(0) := baseCtr

  Seq
    .range(0, tagComponentNum)
    .foreach(i =>
      // skip ctrBitsVec(0)
      queryMetaBundle.providerCtrBits(i + 1) := tagCtrs(i)
    )
//  queryMetaPort.providerCtrBits.drop(1).zip(tagCtrs).foreach {
//    case (dst, src) =>
//      dst := src
//  }
  io.bpuMetaPort         := BpuFtqMetaPort.default
  io.bpuMetaPort.bpuMeta := queryMetaBundle

  ////////////////////////////////////////////////////////////////////////////////////////////
  // Update policy
  ////////////////////////////////////////////////////////////////////////////////////////////

  // USE_ALT_ON_NA
  updateNewEntryFlag := (updateMetaBundle.providerCtrBits(updateProviderId - 1.U) === 3.U(3.W) ||
    updateMetaBundle.providerCtrBits(updateProviderId - 1.U) === 4.U(3.W) &&
    updateProviderId =/= 0.U)
  when(
    updateValid & updateNewEntryFlag &
      updateMetaBundle.useful & ~io.updateInfoPort.predictCorrect
  ) {
    useAltOnNaCounterTables(updatePc(4, 2)) := Mux(useALtOnNaCounter === 15.U(4.W), 15.U(4.W), useALtOnNaCounter + 1.U)
  }.elsewhen(
    updateValid & updateNewEntryFlag &
      updateMetaBundle.useful & io.updateInfoPort.predictCorrect
  ) {
    useAltOnNaCounterTables(updatePc(4, 2)) := Mux(useALtOnNaCounter === 0.U(4.W), 0.U(4.W), useALtOnNaCounter - 1.U)
  }

  // CTR policy
  // Update on a correct prediction: update the ctr bits of the provider
  // Update on a wrong prediction: update the ctr bits of the provider, then allocate an entry in a longer history component
  // Useful policy
  // if pred != altpred, then the pred is useful, and the provider is updated when result come
  // if pred is correct, then increase useful counter, else decrese

  // Update structs
  tagUpdateCtr  := updateCtr.asUInt(tagComponentNum, 1)
  baseUpdateCtr := updateCtr(0)
  // update-prefixed signals are updated related
  updateValid          := io.updateInfoPort.valid
  updatePredictCorrect := io.updateInfoPort.predictCorrect
  updateBranchTaken    := io.updateInfoPort.branchTaken
  updateIsConditional  := io.updateInfoPort.isConditional
  updateProviderId     := updateMetaBundle.providerId
  updateALtProviderId  := updateMetaBundle.altProviderId
  updateALtProviderId  := updateMetaBundle.altProviderId
  updatePc             := io.updatePc

  vecAssign(tagUpdateQueryUsefuls, updateMetaBundle.tagPredictorUsefulBits)

  // Get the ID of desired allocate component
  // This block finds the ID of useful == 0 component
  val tagUpdateQueryUsefulsMatch = RegInit(VecInit(Seq.fill(tagComponentNum)(false.B)))

  Seq.range(0, tagComponentNum).foreach(i => tagUpdateQueryUsefulsMatch(i) := (tagUpdateQueryUsefuls(i) === 0.U(1.W)))

  // Allocation policy, according to TAGE essay
  // Shorter history component has a higher chance of chosen
  // foreach from high index to low index

  tagUpdateUsefulZeroId := 0.U(log2Ceil(tagComponentNum + 1).W)
  tagUpdateQueryUsefulsMatch.zipWithIndex.reverse.foreach {
    case (isMatch, index) =>
      when(
        isMatch && (index.asUInt(log2Ceil(tagComponentTagWidth)) + 1.U(
          log2Ceil(tagComponentTagWidth).W
        ) > updateProviderId)
      ) {
        when(tagUpdateUsefulPingpongCounter(index)) {
          tagUpdateUsefulZeroId := index.U((log2Ceil(tagComponentNum + 1).W))
        }
      }
  }

  // LFSR (Linear-feedback shift regIRegInitister )& Ping-pong counter
  // which is use to generate random number
  tagUpdateUsefulPingpongCounter := randomR(tagComponentNum - 1, 0)

  // Fill update structs
  // update ctr Policy
  // update provider
  when(updateIsConditional.orR & updateValid) {
    updateCtr(updateProviderId) := true.B
  }

  // update altProvider if new entry
  when(
    updateNewEntryFlag && updateIsConditional.orR &&
      !io.updateInfoPort.predictCorrect && updateValid
  ) {
    updateCtr(updateALtProviderId) := true.B
  }

  // tag update policy

  // Default 0
//  val tagUpdateUseful       = WireDefault(0.U((tagComponentNum + 1).W))
//  val tagUpdateIncUseful    = WireDefault(0.U((tagComponentNum + 1).W))
//  val tagUpdateReallocEntry = WireDefault(0.U((tagComponentNum + 1).W))

  // Only update on conditional branches
  when(updateIsConditional.xorR & updateValid) {
    when(updatePredictCorrect.xorR) {
      // if useful,update useful bits
      tagUpdateUseful(updateProviderId - 1.U) := updateMetaBundle.useful
      // Increase if correct, else decrease
      tagUpdateIncUseful(updateProviderId - 1.U) := io.updateInfoPort.predictCorrect
    }.otherwise {
      // Allocate new entry if mispredict
      // Allocate entry in longer history component
      when(tagUpdateUsefulZeroId > updateProviderId) {
        // Have found a slot to allocate
        tagUpdateReallocEntry(tagUpdateUsefulZeroId - 1.U) := true.B
        when(updateNewEntryFlag) {
          tagUpdateUseful(updateProviderId - 1.U) := true.B
        }
      }.otherwise {
        // No slot to allocate, decrease all useful bits of longer history components
        Seq.range(0, tagComponentNum).foreach { i =>
          tagUpdateUseful(i)    := true.B
          tagUpdateIncUseful(i) := false.B
        }
      } // end otherwise
    } // end otherwise
  } // end update

  // generate new tag
  Seq.range(0, tagComponentNum).foreach { i =>
    tagUpdateNewTags(i) := Mux(
      tagUpdateReallocEntry(i),
      updateMetaBundle.tagPredictorQueryTag(i),
      updateMetaBundle.tagPredictorOriginTag(i)
    )
  }

//  // counter
//  Seq.range(0, tagComponentNum + 1).foreach { i =>
//    io.perfTagHitCounters(i) := io.perfTagHitCounters(i) + Cat(0.U(31.W), (i.U === predPredictionId))
//  }

  // todo debug

} //tagePredictor
