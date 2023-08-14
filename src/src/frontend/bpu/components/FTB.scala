package frontend.bpu.components

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import frontend.bpu.components.Bundles.FtbEntryNdPort
import memory.VSimpleDualBRam
import spec._

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
    val hitIndex       = Output(UInt(nwayWidth.W))
    val hit            = Output(Bool())

    // Update signals
    val updatePc        = Input(UInt(addr.W))
    val updateWayIndex  = Input(UInt(nwayWidth.W))
    val updateValid     = Input(Bool())
    val updateDirty     = Input(Bool())
    val updateEntryPort = Input(new FtbEntryNdPort)
  })

//  def toEntryLine(line: UInt) = {
//    val bundle = Wire(new FtbEntryNdPort)
//    bundle.valid            := line(FtbEntryNdPort.width - 1)
//    bundle.isCrossCacheline := line(FtbEntryNdPort.width - 2)
//    bundle.branchType       := line(FtbEntryNdPort.width - 3, FtbEntryNdPort.width - 2 - Param.BPU.BranchType.width)
//    bundle.tag              := line(FtbEntryNdPort.width - 3 - Param.BPU.BranchType.width, spec.Width.Mem._addr * 2)
//    bundle.jumpTargetAddr   := line(spec.Width.Mem._addr * 2 - 1, spec.Width.Mem._addr)
//    bundle.fallThroughAddr  := line(spec.Width.Mem._addr - 1, 0)
//    bundle
//  }
  def toEntryLine(line: UInt) = {
    val bundle = Wire(new FtbEntryNdPort)
    bundle.valid            := line(FtbEntryNdPort.width - 1)
    bundle.isCrossCacheline := line(FtbEntryNdPort.width - 2)
    bundle.branchType       := line(FtbEntryNdPort.width - 3, FtbEntryNdPort.width - 2 - Param.BPU.BranchType.width)
    bundle.fetchLength := line(
      FtbEntryNdPort.width - 3 - Param.BPU.BranchType.width,
      Param.BPU.FTB.tagWidth + spec.Width.Mem._addr
    )
    bundle.tag            := line(Param.BPU.FTB.tagWidth - 1 + spec.Width.Mem._addr, spec.Width.Mem._addr)
    bundle.jumpTargetAddr := line(spec.Width.Mem._addr - 1, 0)
    bundle
  }

  // Signals definition
  val wayQueryEntry = Wire(Vec(nway, new FtbEntryNdPort))
  val wayHits       = Wire(Vec(nway, Bool()))
  val wayHitIndex   = Wire(UInt(nwayWidth.W))
  // Query
  val queryIndex  = Wire(UInt(nsetWidth.W))
  val queryTagReg = Reg(UInt((addr - nsetWidth - 2).W))
  // Update
  val updateIndex     = Wire(UInt(nsetWidth.W))
  val updateEntryPort = Wire(new FtbEntryNdPort)
  val updateWE        = Wire(Vec(nway, Bool()))
  // LFSR (Linear-feedback shift regIRegInitister )& Ping-pong counter
  // which is use to generate random number
  val randomNum = LFSR(width = 16)

  // Query logic
  queryIndex  := io.queryPc(nsetWidth + 1, 2)
  queryTagReg := io.queryPc(addr - 1, nsetWidth + 2)

  wayHits.zip(wayQueryEntry).foreach {
    case (isHit, wayEntry) =>
      isHit := wayEntry.valid && wayEntry.tag === queryTagReg
  }

  // Query output
  io.queryEntryPort := wayQueryEntry(wayHitIndex)
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

  val phtRams = Seq.fill(nway)(
    Module(
      new VSimpleDualBRam(
        nset,
        FtbEntryNdPort.width
      )
    )
  )
  phtRams.zipWithIndex.foreach {
    case (ram, index) =>
      wayQueryEntry(index) := toEntryLine(ram.io.dataOut)
      ram.io.readAddr      := queryIndex
      ram.io.isWrite       := updateWE(index)
      ram.io.dataIn        := updateEntryPort.asUInt
      ram.io.writeAddr     := updateIndex
  }
}
