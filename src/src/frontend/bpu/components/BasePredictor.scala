package frontend.bpu.components

import chisel3._
import chisel3.util._
import frontend.bpu.utils.Bram
import memory.VBRam
import spec.{Param, Width}

class BasePredictor(
  nway:       Int = Param.BPU.FTB.nway,
  ctrWidth:   Int = Param.BPU.TagePredictor.componentCtrWidth(0),
  tableDepth: Int = Param.BPU.TagePredictor.componentTableDepth(0),
  addr:       Int = spec.Width.Mem._addr)
    extends Module {
  // param
  val tableDepthLog = log2Ceil(tableDepth)
  val addrLog       = log2Ceil(addr)

  val io = IO(new Bundle {
    val pc      = Input(UInt(Width.Reg.data))
    val isTaken = Output(Bool())
    val ctr     = Output(UInt(ctrWidth.W)) // counter, use to show predictor state

    // Update
    val updateValid = Input(Bool())
    val updatePc    = Input(UInt(addr.W))
    val isCtrInc    = Input(Bool()) // if istaken from backend,counter inc 1
    val updateCtr   = Input(UInt(ctrWidth.W))
  })

  // Query logic
  val queryIndex = WireDefault(0.U(tableDepthLog.W))
  val queryEntry = WireDefault(0.U(ctrWidth.W))

  queryIndex := io.pc(1 + tableDepthLog, 2)
  // base predictor ctrWidth == 2

  // 00-.weakly taken; 01->stronglty taken;  10-> strongly not taken' 11->weakly not taken
  io.isTaken := (queryEntry(ctrWidth - 1) === 0.U(ctrWidth.W))
  io.ctr     := queryEntry

  // update logic
  val updateIndex   = WireDefault(0.U(tableDepthLog.W))
  val updateContent = WireDefault(0.U(ctrWidth.W))

  updateIndex := io.updatePc(tableDepthLog + 1, 2)

  // base predictor state ctr change when isCtrInc
  // 10 strongly not taken => 11 weakly not taken => 00 weakly taken => 01 strongly taken
  when(io.updateValid) {
    when(io.updateCtr === Cat(0.U(1.W), 1.U((ctrWidth - 1).W))) { // b01
      updateContent := Mux(io.isCtrInc, io.updateCtr, io.updateCtr - 1.U(ctrWidth.W))
    }.elsewhen(io.updateCtr === Cat(1.U(1.W), 0.U((ctrWidth - 1).W))) { // b10
      updateContent := Mux(io.isCtrInc, io.updateCtr + 1.U(ctrWidth.W), io.updateCtr)
    }.otherwise {
      updateContent := Mux(io.isCtrInc, io.updateCtr + 1.U(ctrWidth.W), io.updateCtr - 1.U(ctrWidth.W))
    }
  }.otherwise {
    updateContent := 0.U(ctrWidth.W)
  }
// TODO connect bram with one read and one write port
//  val ctrRam = Module(new VBRam(
//
//  ))
// Table
// Port A as read port, Port B as write port
  val phtTable = Module(
    new Bram(
      dataWidth     = ctrWidth,
      dataDepthExp2 = tableDepthLog
    )
  )
  phtTable.io.ena   := true.B
  phtTable.io.enb   := true.B
  phtTable.io.wea   := false.B
  phtTable.io.web   := io.updateValid
  phtTable.io.dina  := 0.U(ctrWidth.W)
  phtTable.io.addra := queryIndex
  queryEntry        := phtTable.io.douta
  phtTable.io.dinb  := updateContent
  phtTable.io.addrb := updateIndex
  phtTable.io.doutb <> DontCare

}
