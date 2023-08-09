package frontend.bpu

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import frontend.bpu.bundles.{BpuFtqMetaNdPort, TagePredictorUpdateInfoPort}
import frontend.bpu.components.Bundles.TageMetaPort
import frontend.bpu.components._
import spec.Param.BPU.GhrFixType
import spec._
class GhrUpdateNdBundle extends Bundle {
  val bpuSpecValid     = Bool() // speculative update
  val bpuSpecTaken     = Bool()
  val isFixGhrValid    = Bool()
  val ghrFixType       = UInt(Param.BPU.GhrFixType.width.W)
  val isFixBranchTaken = Bool()
}
object GhrUpdateNdBundle {
  def default = 0.U.asTypeOf(new GhrUpdateNdBundle)
}
// TAGE predictor
// This is the main predictor
class TagePredictor(
  tagComponentNum:      Int      = Param.BPU.TagePredictor.tagComponentNum,
  tagComponentTagWidth: Int      = Param.BPU.TagePredictor.tagComponentTagWidth,
  ghrDepth:             Int      = Param.BPU.TagePredictor.ghrLength,
  historyLengths:       Seq[Int] = Param.BPU.TagePredictor.componentHistoryLength,
  phtDepths:            Seq[Int] = Param.BPU.TagePredictor.componentTableDepth,
  componentCtrWidth:    Seq[Int] = Param.BPU.TagePredictor.componentCtrWidth,
  componentUsefulWidth: Seq[Int] = Param.BPU.TagePredictor.componentUsefulWidth,
  entryNum:             Int      = Param.BPU.RAS.entryNum,
  addr:                 Int      = spec.Width.Mem._addr)
    extends Module {
  val addrWidth      = log2Ceil(addr)
  val pointerWidth   = log2Ceil(entryNum)
  val tagComPtrWidth = log2Ceil(tagComponentNum + 1)
  val io = IO(new Bundle {
    // Query signal
    val pc                 = Input(UInt(Width.Reg.data))
    val tageQueryMeta      = Output(new TageMetaPort)
    val predictBranchTaken = Output(Bool())
    val predictValid       = Output(Bool())

    // Update signals
    val updatePc       = Input(UInt(Width.Reg.data))
    val updateInfoPort = Input(new TagePredictorUpdateInfoPort)

    // BPU predict info
    val ghrUpdateNdBundle = Input(new GhrUpdateNdBundle)
    val bpuSpecValid      = Input(Bool()) // speculative update
    val bpuSpecTaken      = Input(Bool())

    // TODO PMU
    //    val perfTagHitCounters = Output(Vec(32, UInt((tagComponentNum + 1).W)))

  })

  def vecAssign[T <: Data](dst: Vec[T], src: Vec[T]): Unit = {
    dst.zip(src).foreach {
      case (a, b) =>
        a := b
    }
  }
  // Input
  val updateMetaBundle = WireDefault(io.updateInfoPort.tageOriginMeta)

  // Signals
  // // Query
  // val takens = WireDefault(VecInit(Seq.fill(tagComponentNum + 1)(false.B)))

  // Base predictor
  val baseIsTaken = WireDefault(true.B)
  val baseCtrbit  = WireDefault(0.U(componentCtrWidth(0).W)) // initial state : weakly taken

  // Tagged predictor
  // The provider id of the accepted prediction, selected using priority encoder
  val predPredictionId = WireDefault(0.U(tagComPtrWidth.W))
  // The provider id of the last hit provider
  val altPredPredctionId = WireDefault(0.U(tagComPtrWidth.W))
  // For example, provider 2,4 hit, and provider 1,3 missed
  // then pred is 4, and altpred is 2
  val tagIsTakens       = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagIsHits         = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val queryNewEntryFlag = WireDefault(false.B) // Indicates the provider is new

  // Meta
  val tagCtrbits    = WireDefault(VecInit(Seq.fill(tagComponentNum)(0.U(3.W))))
  val tagUsefulbits = WireDefault(VecInit(Seq.fill(tagComponentNum)(0.U(componentUsefulWidth(1).W))))
  val tagQueryTags  = WireDefault(VecInit(Seq.fill(tagComponentNum)(0.U(tagComponentTagWidth.W))))
  val tagOriginTags = WireDefault(VecInit(Seq.fill(tagComponentNum)(0.U(tagComponentTagWidth.W))))
  val tagHitIndexs  = WireDefault(VecInit(Seq.fill(tagComponentNum)(0.U(10.W))))

  // update
  val updatePc                 = WireDefault(0.U(Width.Reg.data))
  val isBaseUpdateCtr          = WireDefault(false.B)
  val isUpdateValid            = WireDefault(false.B)
  val isGlobalHistoryUpdateReg = RegInit(false.B)
  val updatePredictCorrect     = WireDefault(false.B)
  val updateBranchTaken        = WireDefault(false.B)
  val updateIsConditional      = WireDefault(false.B)
  val updateNewEntryFlag       = WireDefault(false.B) // Indicates the provider is new
  val updateProviderId         = WireDefault(0.U(tagComPtrWidth.W))
  val updateALtProviderId      = WireDefault(0.U(tagComPtrWidth.W))
  val isUpdateCtrVec = WireDefault(
    VecInit(Seq.fill(tagComponentNum + 1)(false.B))
  ) // Whether a component should updated its ctr
  val tagIsUpdateCtrs          = WireDefault(0.U(tagComponentNum.W))
  val tagIsUpdateUsefuls       = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagUpdateIsIncUsefuls    = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagUpdateisReallocEntrys = WireDefault(VecInit(Seq.fill(tagComponentNum)(false.B)))
  val tagUpdateQueryUsefulbits = WireDefault(VecInit(Seq.fill(tagComponentNum)(0.U(3.W))))
  val tagUpdateNewTags         = WireInit(VecInit(Seq.fill(tagComponentNum)(0.U(tagComponentTagWidth.W))))

  // // Indicates the longest history component which useful is 0
  // val tagUpdateUsefulZeroId = WireDefault(0.U(tagComPtrWidth.W))

  // pingpong counter & lfsr
  // is a random number array
  val randomR                        = LFSR(width = 16)
  val tagUpdateUsefulPingpongCounter = WireDefault(0.U(tagComponentNum.W))

  // USE_ALT_ON_NA counter
  val useAltOnNaCounterTablesReg = RegInit(VecInit(Seq.fill(8)(0.U(4.W))))
  val useALtOnNaCounter          = WireDefault(0.U(4.W))
  val isUseAlt                   = WireDefault(false.B)

  //  integer use_alt_cnt;

  ////////////////////////////////////////////////////////////////////////////////////////////
  // END of Defines
  ////////////////////////////////////////////////////////////////////////////////////////////
  // Global History Register speculative update logic
  ////////////////////////////////////////////////////////////////////////////////////////////

  val specPtr     = RegInit(0.U(Param.BPU.ftqPtrWidth.W))
  val nextSpecPtr = Wire(UInt(Param.BPU.ftqPtrWidth.W))
  val commitPtr   = RegInit(0.U(Param.BPU.ftqPtrWidth.W))
  val checkPtr    = WireDefault(0.U(Param.BPU.ftqPtrWidth.W))
  val checkDepth  = Wire(UInt(Param.BPU.ftqPtrWidth.W))
  checkDepth := checkPtr - commitPtr // calculate the location of the correct old history

  // Global History Register
  val ghr     = RegInit(0.U(ghrDepth.W))
  val nextGhr = Wire(UInt(ghrDepth.W))
  // default nextGhr keep; ghr = RegNext(nextGhr)
  nextGhr     := ghr
  ghr         := nextGhr
  nextSpecPtr := specPtr
  specPtr     := nextSpecPtr

  isGlobalHistoryUpdateReg := isUpdateValid

  // speculative Global history update
  // 1. when ftb hit and predict ,update ghr with predictBranchTaken, specPtr + 1
  // 2. when inst commit, commitPtr + 1
  // fix error global history
  // 1. when check a direction error, Cat the correct old history and actually taken as fixed history,specPtr keep as checkPtr
  // 2. when check a branch inst actually is taken without prediction, update the history with checkPoint,specPtr = 1 + checkPtr
  // 3. when detect a non branch inst predict taken or an exception that discards a predicted inst,recover the history, specPtr = checkPtr - 1
  // ghr update priority:
  // 1.commitStage : recover
  // 2.exeStage :  fix or update
  // 3.preDecoderStage :recover or update
  // 4.spec update

  when(isUpdateValid) {
    commitPtr := commitPtr + 1.U
  }
  when(io.ghrUpdateNdBundle.isFixGhrValid) {
    switch(io.ghrUpdateNdBundle.ghrFixType) {
      is(GhrFixType.commitBrExcp, GhrFixType.decodeBrExcp) {
        nextGhr     := ghr >> checkDepth // recover to the old history
        nextSpecPtr := checkPtr - 1.U
      }
      is(GhrFixType.exeFixDirection) {
        nextGhr     := Cat(ghr >> checkDepth, io.ghrUpdateNdBundle.isFixBranchTaken) // fix error predict
        nextSpecPtr := checkPtr
      }
      is(GhrFixType.exeUpdateJump, GhrFixType.decodeUpdateJump) {
        nextGhr     := Cat(ghr >> checkDepth, true.B) // update the branch that has not been predicted
        nextSpecPtr := checkPtr + 1.U
      }
    }
  }.elsewhen(io.bpuSpecValid) {
    nextGhr     := Cat(ghr(ghrDepth - 2, 0), io.bpuSpecTaken)
    nextSpecPtr := specPtr + 1.U
  }

  when(isUpdateValid) {
    nextGhr := Cat(ghr(ghrDepth - 2, 0), updateBranchTaken)
  }
  // output
  io.tageQueryMeta.checkPtr := nextSpecPtr

  ////////////////////////////////////////////////////////////////////////////////////////////
  // Query Logic
  ////////////////////////////////////////////////////////////////////////////////////////////

  // Base Predictor
  val basePredictor = Module(new BasePredictor())
  basePredictor.io.pc          := io.pc
  basePredictor.io.updateValid := isBaseUpdateCtr
  baseIsTaken                  := basePredictor.io.isTaken
  baseCtrbit                   := basePredictor.io.ctr
  basePredictor.io.updatePc    := updatePc
  basePredictor.io.isCtrInc    := updateBranchTaken
  basePredictor.io.updateCtr   := updateMetaBundle.providerCtrBits(0)

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
      taggedPreditor.io.isGlobalHistoryUpdate := isUpdateValid
      taggedPreditor.io.globalHistory         := nextGhr(historyLengths(providerId + 1) - 1, 0)
      taggedPreditor.io.pc                    := io.pc
      tagUsefulbits(providerId)               := taggedPreditor.io.usefulBits
      tagCtrbits(providerId)                  := taggedPreditor.io.ctrBits
      tagQueryTags(providerId)                := taggedPreditor.io.queryTag
      tagOriginTags(providerId)               := taggedPreditor.io.originTag
      tagHitIndexs(providerId)                := taggedPreditor.io.hitIndex
      tagIsTakens(providerId)                 := taggedPreditor.io.taken
      tagIsHits(providerId)                   := taggedPreditor.io.tagHit

      // update
      taggedPreditor.io.updatePc         := io.updatePc
      taggedPreditor.io.updateValid      := isUpdateValid && updateIsConditional
      taggedPreditor.io.incUseful        := tagUpdateIsIncUsefuls(providerId)
      taggedPreditor.io.updateUseful     := tagIsUpdateUsefuls(providerId)
      taggedPreditor.io.updateUsefulBits := updateMetaBundle.tagPredictorUsefulBits(providerId)
      taggedPreditor.io.updateCtr        := tagIsUpdateCtrs.asBools(providerId)
      taggedPreditor.io.incCtr           := updateBranchTaken
      taggedPreditor.io.updateCtrBits    := updateMetaBundle.providerCtrBits(providerId + 1)
      taggedPreditor.io.reallocEntry     := tagUpdateisReallocEntrys(providerId)
      taggedPreditor.io.updateTag        := tagUpdateNewTags(providerId)
      taggedPreditor.io.updateIndex      := updateMetaBundle.tagPredictorHitIndexs(providerId)

      taggedPreditor
    }
  }

  // Select the longest match provider
  // predPredictionId = 0.U((tagComponentNum + 1).W)
  tagIsHits.zipWithIndex.foreach {
    case (oneTagHit, idx) =>
      when(oneTagHit) {
        predPredictionId := (idx + 1).U
      }
  }

  // Select altpred
  val altpredPools = WireDefault(VecInit(Seq.fill(tagComponentNum + 1)(false.B)))
  //  altpredPool    := Cat(tagHit, 1.U(1.W))
  // Seq.range(1, tagComponentNum + 1).foreach(i => altpredPool(i) := tagHit(i - 1))
  // altpredPool(0) := true.B
  val altprePool = WireDefault(VecInit(Seq(true.B) ++ tagIsHits))
  when(predPredictionId =/= 0.U) {
    altpredPools(predPredictionId) := false.B
  }

  // altPredPredctionId = 0.U((tagComponentNum + 1).W)
  altprePool.zipWithIndex.foreach {
    case (altPred, idx) =>
      when(altPred) {
        altPredPredctionId := idx.U
      }
  }

  // Output logic
  io.predictValid := true.B

  // Query
  //  takens          := Cat(tagTaken, baseTaken)
  val takens = VecInit(Seq(baseIsTaken) ++ tagIsTakens)
  queryNewEntryFlag := ((tagCtrbits(predPredictionId - 1.U) === 3.U ||
    tagCtrbits(predPredictionId - 1.U) === 4.U)) &&
    predPredictionId =/= 0.U
  useALtOnNaCounter := useAltOnNaCounterTablesReg(io.pc(4, 2))
  isUseAlt          := (useALtOnNaCounter(3) === true.B) && queryNewEntryFlag

  // assign useAlt = 0
  io.predictBranchTaken := takens(predPredictionId)

  // Meta
  val queryMetaBundle = WireDefault(TageMetaPort.default)
  vecAssign(queryMetaBundle.tagPredictorUsefulBits, tagUsefulbits)
  vecAssign(queryMetaBundle.tagPredictorHitIndexs, tagHitIndexs)
  vecAssign(queryMetaBundle.tagPredictorQueryTags, tagQueryTags)
  vecAssign(queryMetaBundle.tagPredictorOriginTags, tagOriginTags)
  queryMetaBundle.isUseful := takens(predPredictionId) =/= takens(
    altPredPredctionId
  ) // Indicates whether the pred component is useful
  queryMetaBundle.providerId    := predPredictionId
  queryMetaBundle.altProviderId := altPredPredctionId
  queryMetaBundle.providerCtrBits.zip(Seq(baseCtrbit) ++ tagCtrbits).foreach {
    case (dst, src) =>
      dst := src
  }

  io.tageQueryMeta := queryMetaBundle

  ////////////////////////////////////////////////////////////////////////////////////////////
  // Update policy
  ////////////////////////////////////////////////////////////////////////////////////////////

  // USE_ALT_ON_NA
  // ctr = b100 or b011 means has no confidence;so update new entry
  updateNewEntryFlag :=
    (updateMetaBundle.providerCtrBits(updateProviderId - 1.U) === 3.U ||
      updateMetaBundle.providerCtrBits(updateProviderId - 1.U) === 4.U) &&
      updateProviderId =/= 0.U
  when(
    isUpdateValid &&
      updateNewEntryFlag &&
      updateMetaBundle.isUseful
  ) {
    useAltOnNaCounterTablesReg(updatePc(4, 2)) := Mux(
      io.updateInfoPort.predictCorrect,
      Mux(useALtOnNaCounter === 0.U, 0.U, useALtOnNaCounter - 1.U),
      Mux(
        useALtOnNaCounter === 15.U,
        15.U,
        useALtOnNaCounter + 1.U
      )
    )
  }

  // CTR policy
  // Update on a correct prediction: update the ctr bits of the provider
  // Update on a wrong prediction: update the ctr bits of the provider, then allocate an entry in a longer history component
  // Useful policy
  // if pred != altpred, then the pred is useful, and the provider is updated when result come
  // if pred is correct, then increase useful counter, else decrese

  // Update structs
  tagIsUpdateCtrs := isUpdateCtrVec.asUInt(tagComponentNum, 1)
  isBaseUpdateCtr := isUpdateCtrVec(0)
  // update-prefixed signals are updated related
  isUpdateValid        := io.updateInfoPort.valid
  updatePredictCorrect := io.updateInfoPort.predictCorrect
  updateBranchTaken    := io.updateInfoPort.branchTaken
  updateIsConditional  := io.updateInfoPort.isConditional
  updateProviderId     := updateMetaBundle.providerId
  updateALtProviderId  := updateMetaBundle.altProviderId
  updateALtProviderId  := updateMetaBundle.altProviderId
  updatePc             := io.updatePc

  vecAssign(tagUpdateQueryUsefulbits, updateMetaBundle.tagPredictorUsefulBits)

  // Get the ID of desired allocate component
  // This block finds the ID of useful == 0 component
  val tagUpdateQueryUsefulsMatch = VecInit(tagUpdateQueryUsefulbits.map(_ === 0.U))

  // Allocation policy, according to TAGE essay
  // Shorter history component has a higher chance of chosen
  // foreach from high index to low index

  // Indicates the longest history component which useful is 0
  val tagUpdateUsefulZeroId = WireDefault(0.U(tagComPtrWidth.W))
  tagUpdateQueryUsefulsMatch.zipWithIndex.reverse.foreach {
    case (isMatch, index) =>
      when(
        isMatch && (index.U(tagComPtrWidth.W) + 1.U > updateProviderId)
      ) {
        when(tagUpdateUsefulPingpongCounter(index)) {
          tagUpdateUsefulZeroId := (index + 1).U
        }
      }
  }

  // LFSR (Linear-feedback shift regIRegInitister )& Ping-pong counter
  // which is use to generate random number
  tagUpdateUsefulPingpongCounter := randomR(tagComponentNum - 1, 0)

  // Fill update structs
  // update ctr Policy
  // update provider
  when(updateIsConditional && isUpdateValid) {
    isUpdateCtrVec(updateProviderId) := true.B
  }

  // update altProvider if new entry
  when(
    updateNewEntryFlag && updateIsConditional &&
      !io.updateInfoPort.predictCorrect && isUpdateValid
  ) {
    isUpdateCtrVec(updateALtProviderId) := true.B
  }

  // tag update policy

  // Default 0
  //  val tagUpdateUseful       = WireDefault(0.U((tagComponentNum + 1).W))
  //  val tagUpdateIncUseful    = WireDefault(0.U((tagComponentNum + 1).W))
  //  val tagUpdateReallocEntry = WireDefault(0.U((tagComponentNum + 1).W))

  // Only update on conditional branches
  when(updateIsConditional && isUpdateValid) {
    when(updatePredictCorrect) {
      // if useful,update useful bits
      tagIsUpdateUsefuls(updateProviderId - 1.U) := updateMetaBundle.isUseful
      // Increase if correct, else decrease
      tagUpdateIsIncUsefuls(updateProviderId - 1.U) := io.updateInfoPort.predictCorrect
    }.otherwise {
      // Allocate new entry if mispredict
      // Allocate entry in longer history component
      when(tagUpdateUsefulZeroId > updateProviderId) {
        // Have found a slot to allocate
        tagUpdateisReallocEntrys(tagUpdateUsefulZeroId - 1.U) := true.B
        when(updateNewEntryFlag) {
          tagIsUpdateUsefuls(updateProviderId - 1.U) := true.B
        }
      }.otherwise {
        // No slot to allocate, decrease all useful bits of longer history components
        tagIsUpdateUsefuls.lazyZip(tagUpdateIsIncUsefuls).zipWithIndex.foreach {
          case ((isUpdate, isIncUseful), idx) =>
            when(idx.U >= updateProviderId - 1.U) {
              isUpdate    := true.B
              isIncUseful := false.B
            }
        }
      } // end otherwise
    } // end otherwise
  } // end update

  // generate new tag
  tagUpdateNewTags
    .lazyZip(tagUpdateisReallocEntrys)
    .lazyZip(updateMetaBundle.tagPredictorQueryTags)
    .lazyZip(updateMetaBundle.tagPredictorOriginTags)
    .foreach {
      case (newTag, isRealloc, queryTag, originTag) =>
        newTag := Mux(isRealloc, queryTag, originTag)
    }

  //  // counter
  //  Seq.range(0, tagComponentNum + 1).foreach { i =>
  //    io.perfTagHitCounters(i) := io.perfTagHitCounters(i) + Cat(0.U(31.W), (i.U === predPredictionId))
  //  }

  // todo debug

} //tagePredictor
