package frontend.bpu.components

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
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
  val wayQueryEntryRegs = Wire(Vec(nway, new FtbEntryNdPort))
  val wayHits           = WireDefault(VecInit(Seq.fill(nway)(false.B)))
  val wayHitIndex       = WireDefault(0.U(nwayWidth.W))
  // Query
  val queryIndex  = WireDefault(0.U(nsetWidth.W))
  val queryTagReg = RegInit(0.U((addr - nsetWidth - 2).W))
  // Update
  val updateIndex     = WireDefault(0.U(nsetWidth.W))
  val updateEntryPort = Reg(new FtbEntryNdPort)
  val updateWE        = WireDefault(VecInit(Seq.fill(nway)(false.B)))
  // LFSR (Linear-feedback shift regIRegInitister )& Ping-pong counter
  // which is use to generate random number
  val randomNum = LFSR(width = 16)

  // Query logic
  queryIndex  := io.queryPc(nsetWidth + 1, 2)
  queryTagReg := RegNext(io.queryPc(addr - 1, nwayWidth + 2))

  wayHits.zip(wayQueryEntryRegs).foreach {
    case (isHit, wayEntry) =>
      isHit := wayEntry.valid && wayEntry.tag === queryTagReg
  }

  // Query output
  io.queryEntryPort := wayQueryEntryRegs(wayHitIndex)
  io.hit            := wayHits.asUInt.orR
  io.hitIndex       := wayHitIndex

  // Update logic
  updateIndex := io.updatePc(nsetWidth + 1, 2)
  when(io.updateDirty) {
    // Just override all entry in this group to ensure old entry is cleared
    updateEntryPort := io.updateEntryPort
    updateWE.zipWithIndex.foreach {
      case (en, index) =>
        en := Mux(index.U === io.updateWayIndex, io.updateValid, false.B)
    }
  }.otherwise {
    // Update a new entry in
    updateEntryPort := io.updateEntryPort
    updateWE.zipWithIndex.foreach {
      case (en, index) =>
        en := Mux(index.U === randomNum(nwayWidth - 1, 0), io.updateValid, false.B)
    }
  }

  // hit Priority
  wayHitIndex := PriorityEncoder(wayHits)

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
      wayQueryEntryRegs(wayIdx) := bram.io.douta.asTypeOf(new FtbEntryNdPort)
      bram.io.dinb              := updateEntryPort.asUInt
      bram.io.addrb             := updateIndex
      bram.io.doutb             <> DontCare
      bram
    })
}
