package frontend.bpu.components
import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import frontend.bpu.utils.{CsrHash, DebugCsrHash}
import memory.VSimpleDualBRam
import spec.Param

class TaggedPreditor(
  ghrLength:      Int = 4,
  phtDepth:       Int = 1024,
  phtTagWidth:    Int = Param.BPU.TagePredictor.tagComponentTagWidth,
  phtCtrWidth:    Int = 3,
  phtUsefulWidth: Int = 2,
  addr:           Int = spec.Width.Mem._addr)
    extends Module {
  // param
  val phtAddrWidth = log2Ceil(phtDepth)

  // define bundle
  class PhtEntey extends Bundle {
    val counter = UInt(phtCtrWidth.W)
    val useful  = UInt(phtUsefulWidth.W)
    val tag     = UInt(phtTagWidth.W)
  }
  object PhtEntey {
    def default = (new PhtEntey).Lit(
      // counter highest bit as 1,other 0;means weakly taken
      _.counter -> (1 << (phtCtrWidth - 1)).U,
      _.useful -> 0.U,
      _.tag -> io.updateTag
    )
    def width = phtCtrWidth + phtTagWidth + phtUsefulWidth
  }

  val io = IO(new Bundle {
    // Query signal
    val isGlobalHistoryUpdate      = Input(Bool())
    val globalHistory              = Input(UInt((ghrLength).W))
    val debugGlobalHistory         = Input(UInt((ghrLength).W))
    val pc                         = Input(UInt(spec.Width.Mem.addr))
    val debugIsGlobalHistoryUpdate = Input(Bool())

    // Meta
    val usefulBits       = Output(UInt(phtUsefulWidth.W))
    val ctrBits          = Output(UInt(phtCtrWidth.W))
    val queryTag         = Output(UInt(phtTagWidth.W))
    val originTag        = Output(UInt(phtTagWidth.W))
    val hitIndex         = Output(UInt(log2Ceil(phtDepth).W))
    val queryGhtHash     = Output(UInt(phtAddrWidth.W))
    val queryTagHashCsr1 = Output(UInt(phtTagWidth.W))
    val queryTagHashCsr2 = Output(UInt((phtTagWidth - 1).W))

    // Query result
    val taken  = Output(Bool())
    val tagHit = Output(Bool())

    // Update signals
    val updateValid      = Input(Bool())
    val updatePc         = Input(UInt(spec.Width.Mem.addr)) // has been hash to updateIndex
    val updateUseful     = Input(Bool())
    val incUseful        = Input(Bool())
    val updateUsefulBits = Input(UInt(phtUsefulWidth.W))
    val updateCtr        = Input(Bool())
    val incCtr           = Input(Bool())
    val updateCtrBits    = Input(UInt(phtCtrWidth.W))
    val reallocEntry     = Input(Bool())
    val updateTag        = Input(UInt(phtTagWidth.W))
    val updateIndex      = Input(UInt(log2Ceil(phtDepth).W))

    // speculative update hash value
    val isFixHash         = Input(Bool())
    val isRecoverHash     = Input(Bool())
    val originGhtHash     = Input(UInt(phtAddrWidth.W))
    val originTagHashCsr1 = Input(UInt(phtTagWidth.W))
    val originTagHashCsr2 = Input(UInt((phtTagWidth - 1).W))
  })

  def toPhtLine(line: UInt) = {
    val bundle = Wire(new PhtEntey)
    bundle.counter := line(PhtEntey.width - 1, PhtEntey.width - phtCtrWidth)
    bundle.useful  := line(PhtEntey.width - phtCtrWidth - 1, phtTagWidth)
    bundle.tag     := line(phtTagWidth - 1, 0)

    bundle
  }
  ////////////////////////////////////////////////////////////////////////////////////////////
  // Query logic
  ////////////////////////////////////////////////////////////////////////////////////////////
  // Query Index
  // Fold GHT input to a fix length, the same as index range
  // Using a CSR, described in PPM-Liked essay
  val hashedGhtInput = Wire(UInt(phtAddrWidth.W))
  // query_index is Fold(GHR) ^ PC[low] ^ PC[high]
  val queryIndex = WireDefault(io.pc(phtAddrWidth - 1, 0) ^ io.pc(2 * phtAddrWidth - 1, phtAddrWidth) ^ hashedGhtInput)
//  val queryIndex = WireDefault(
//    io.pc(phtAddrWidth , 1) ^ io.pc(2 * phtAddrWidth + 2, phtAddrWidth + 1) ^ hashedGhtInput
//  )
  val queryIndexReg = Reg(UInt(phtTagWidth.W))
  queryIndexReg := queryIndex

  // Tag
  // Generate another hash different from above, as described in PPM-Liked essay
  val tagHashCsr1 = Wire(UInt(phtTagWidth.W))
  val tagHashCsr2 = Wire(UInt((phtTagWidth - 1).W))
  // query_tag is XORed from pc_i
  // assign query_tag = pc_i[31:31-PHT_TAG_WIDTH+1];
  val queryTag    = WireDefault(io.pc(1 + phtTagWidth, 2) ^ tagHashCsr1 ^ Cat(tagHashCsr2, 0.U(1.W)))
  val queryTagReg = Reg(UInt(phtTagWidth.W))
  queryTagReg := queryTag

  // PHT
  // result
  // default ctr topbit:1 otherbits:0 (weakly taken)
  // update entry
  val phtQueryResult  = Wire(new PhtEntey)
  val phtUpdateResult = Wire(new PhtEntey)

  val phtUpdateIndex = Wire(UInt(phtAddrWidth.W))

  // Output
  io.ctrBits    := phtQueryResult.counter
  io.usefulBits := phtQueryResult.useful
  io.hitIndex   := queryIndexReg
  io.queryTag   := queryTagReg
  io.originTag  := phtQueryResult.tag
  io.taken      := (phtQueryResult.counter(phtCtrWidth - 1) === 1.U)
  io.tagHit     := (queryTagReg === phtQueryResult.tag)

  ///////////////////////////////////////////////////////////////////////////////////////////
  // Update logic
  ///////////////////////////////////////////////////////////////////////////////////////////
  // update CTR bits
  when(io.updateCtr) {
    // default value
    phtUpdateResult.counter := Mux(io.incCtr, io.updateCtrBits + 1.U, io.updateCtrBits - 1.U)
    val zero    = 0.U(phtCtrWidth.W)
    val fullOne = (-1.S(phtCtrWidth.W)).asUInt
    when(io.updateCtrBits === zero) {
      // min valuedon't decrease
      phtUpdateResult.counter := Mux(io.incCtr, 1.U(phtCtrWidth.W), 0.U(phtCtrWidth.W))
    }.elsewhen(io.updateCtrBits === fullOne) {
      // max value dont' increase
      phtUpdateResult.counter := Mux(io.incCtr, (-1.S(phtCtrWidth.W)).asUInt, (-2.S(phtCtrWidth.W)).asUInt)
    }
  }.otherwise {
    phtUpdateResult.counter := io.updateCtrBits
  }
  phtUpdateIndex      := io.updateIndex
  phtUpdateResult.tag := io.updateTag

  // Update useful bits
  when(io.updateUseful) {
    // default value
    phtUpdateResult.useful := Mux(
      io.incUseful,
      io.updateUsefulBits + 1.U(phtUsefulWidth.W),
      io.updateUsefulBits - 1.U(phtUsefulWidth.W)
    )
    when(io.updateUsefulBits === 0.U) {
      // min value don't decrease
      phtUpdateResult.useful := Mux(io.incUseful, 1.U(phtUsefulWidth.W), 0.U(phtUsefulWidth.W))
    }.elsewhen(io.updateUsefulBits === (-1.S(phtUsefulWidth.W)).asUInt) {
      // max value dont' increase
      phtUpdateResult.useful := Mux(io.incUseful, (-1.S(phtUsefulWidth.W)).asUInt, (-2.S(phtUsefulWidth.W)).asUInt)
    }
  }.otherwise {
    phtUpdateResult.useful := io.updateUsefulBits
  }

  // Alocate new entry
  when(io.reallocEntry) {
    // Reset
    phtUpdateResult.useful  := 0.U
    phtUpdateResult.counter := (1 << (phtCtrWidth - 1)).U
    phtUpdateResult.tag     := io.updateTag

    // when realocEntry,clear ctr and useful
    // when realloc ,use query tag; else use origin tag
  }

  // to do  connect CSR hash
  val ghtHashCsrHash = Module(new CsrHash(ghrLength, phtAddrWidth))
  ghtHashCsrHash.io.data          := io.globalHistory
  ghtHashCsrHash.io.dataUpdate    := io.isGlobalHistoryUpdate
  hashedGhtInput                  := ghtHashCsrHash.io.hash
  ghtHashCsrHash.io.originHash    := io.originGhtHash
  ghtHashCsrHash.io.isRecoverHash := io.isRecoverHash
  ghtHashCsrHash.io.isFixHash     := io.isFixHash
  io.queryGhtHash                 := ghtHashCsrHash.io.hash

  val pcHashCsrHash1 = Module(new CsrHash(ghrLength, phtTagWidth))
  pcHashCsrHash1.io.data          := io.globalHistory
  pcHashCsrHash1.io.dataUpdate    := io.isGlobalHistoryUpdate
  tagHashCsr1                     := pcHashCsrHash1.io.hash
  pcHashCsrHash1.io.originHash    := io.originTagHashCsr1
  pcHashCsrHash1.io.isRecoverHash := io.isRecoverHash
  pcHashCsrHash1.io.isFixHash     := io.isFixHash
  io.queryTagHashCsr1             := pcHashCsrHash1.io.hash

  val pcHashCsrHash2 = Module(new CsrHash(ghrLength, phtTagWidth - 1))
  pcHashCsrHash2.io.data          := io.globalHistory
  pcHashCsrHash2.io.dataUpdate    := io.isGlobalHistoryUpdate
  tagHashCsr2                     := pcHashCsrHash2.io.hash
  pcHashCsrHash2.io.originHash    := io.originTagHashCsr2
  pcHashCsrHash2.io.isRecoverHash := io.isRecoverHash
  pcHashCsrHash2.io.isFixHash     := io.isFixHash

  io.queryTagHashCsr2 := pcHashCsrHash2.io.hash

  val phtRam = Module(
    new VSimpleDualBRam(
      phtDepth, // size
      PhtEntey.width // dataWidth
    )
  )
  phtRam.io.readAddr  := queryIndex
  phtQueryResult      := toPhtLine(phtRam.io.dataOut)
  phtRam.io.isWrite   := io.updateValid
  phtRam.io.dataIn    := Cat(phtUpdateResult.counter, phtUpdateResult.useful, phtUpdateResult.tag)
  phtRam.io.writeAddr := phtUpdateIndex

  // debug speculative global history update hash
//  val debugHashedGhtInput = dontTouch(Wire(UInt(phtAddrWidth.W)))
//  val debugGhtHashCsrHash = Module(new DebugCsrHash(ghrLength, phtAddrWidth))
//  debugGhtHashCsrHash.io.data       := io.debugGlobalHistory
//  debugGhtHashCsrHash.io.dataUpdate := io.debugIsGlobalHistoryUpdate
//  debugHashedGhtInput               := debugGhtHashCsrHash.io.hash

}
