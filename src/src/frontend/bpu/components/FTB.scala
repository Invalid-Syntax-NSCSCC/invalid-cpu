package frontend.bpu.components

import chisel3._
import chisel3.util._
import frontend.bpu.components.Bundles.FtbEntryNdPort
import frontend.bpu.utils.{Bram, Lfsr}
import spec._
import utils.BiPriorityMux

// BPU is the Branch Predicting Unit
// BPU does the following things:
// 1. accept update info from FTQ
// 2. provide update to tage predictor
// 3. send pc into tage predictor and generate FTQ block

class FTB(
  nway: Int = Param.BPU.FTB.nway,
  nset: Int = Param.BPU.FTB.nset,
  addr: Int = spec.Width.Mem._addr)
    extends Module {
  // param
  val nwayWidth = log2Ceil(nway)
  val nsetWidth = log2Ceil(nset)

  val io = IO(new Bundle {
    // Query
    val queryPc        = Input(UInt(addr.W))
    val queryEntryPort = Output(new FtbEntryNdPort)
    val hitIndex       = Output(UInt(nway.W))
    val hit            = Output(Bool())

    // Update signals
    val updatePc        = Input(UInt(addr.W))
    val updateWayIndex  = Input(UInt(nway.W))
    val updateValid     = Input(Bool())
    val updateDirty     = Input(Bool())
    val updateEntryPort = Input(new FtbEntryNdPort)
  })

  // Signals definition
  val wayQueryEntryRegs = Vec(nway, new FtbEntryNdPort)
  val wayHits        = WireDefault(0.U(nway.W))
  val wayHitIndex       = WireDefault(0.U(nwayWidth.W))
  // Query
  val queryIndex  = WireDefault(0.U(nsetWidth.W))
  val queryTagReg = RegInit(0.U((addr - nsetWidth - 2).W))
  // Update
  val updateIndex     = WireDefault(0.U(nsetWidth.W))
  val updateEntryPort = Reg(new FtbEntryNdPort)
  val updateWE        = WireDefault(0.U(nway.W))
  val randomR         = WireDefault(0.U(16.W))

  // Query logic
  queryIndex  := io.queryPc(nsetWidth + 1, 2)
  queryTagReg := RegNext(io.queryPc(addr - 1, nwayWidth + 2))

  Seq.range(0, nway).map { wayIndex =>
    wayHits(wayIndex) := (wayQueryEntryRegs(wayIndex).tag === queryTagReg) &&
      wayQueryEntryRegs(wayIndex).valid
    wayHits
  }

  // Query output
  io.queryEntryPort := wayQueryEntryRegs(wayHitIndex)
  io.hit            := wayHits.orR
  io.hitIndex       := wayHitIndex

  // Update logic
  updateIndex := io.updatePc(nsetWidth + 1, 2)
  when(io.updateDirty) {
    // Just override all entry in this group to ensure old entry is cleared
    updateEntryPort             := io.updateEntryPort
    updateWE                    := 0.U(nway.W)
    updateWE(io.updateWayIndex) := io.updateValid
  }.otherwise {
    // Update a new entry in
    updateEntryPort                     := io.updateEntryPort
    updateWE                            := 0.U(nway.W)
    updateWE(randomR(nwayWidth - 1, 0)) := io.updateValid
  }

  // hit Priority
  val isHit         = WireDefault(wayHits.orR)
  val biPriorityMux = Module(new BiPriorityMux(num = nway))
  biPriorityMux.io.inVector := wayHits
  Cat(isHit, wayHitIndex)   := biPriorityMux.io.selectIndices

  // LFSR (Linear-feedback shift regIRegInitister )& Ping-pong counter
  // which is use to generate random number
  val lsfr = new Lfsr(width = 16)
  lsfr.io.en := true.B
  randomR    := lsfr.io.value

  // TODO use blackbox connect bram
  // bram
  Seq
    .range(0, nway)
    .map(wayIdx => {
      val bram = Module(new Bram(dataWidth = FtbEntryNdPort.bitsLength, dataDepthExp2 = nsetWidth))
      bram.io.ena               := true.B
      bram.io.enb               := true.B
      bram.io.wea               := false.B
      bram.io.web               := updateWE(wayIdx)
      bram.io.dina              <> DontCare
      bram.io.addra             := queryIndex
      wayQueryEntryRegs(wayIdx) := bram.io.douta
      bram.io.dinb              := updateEntryPort
      bram.io.addrb             := updateIndex
      bram.io.doutb             <> DontCare
      bram
    })
}
