package frontend.bpu.components

import chisel3._
import chisel3.util._
import memory.VSimpleDualBRam
import spec.{Param, Width}

class BasePredictor(
  nway:       Int = Param.BPU.FTB.nway,
  ctrWidth:   Int = Param.BPU.TagePredictor.componentCtrWidth(0),
  tableDepth: Int = Param.BPU.TagePredictor.componentTableDepth(0),
  addr:       Int = spec.Width.Mem._addr)
    extends Module {
  // param
  val tableDepthLog = log2Ceil(tableDepth)

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
  io.isTaken := queryEntry(ctrWidth - 1) === 0.U
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

  val ctrRam = Module(
    new VSimpleDualBRam(
      tableDepth, // size
      ctrWidth // dataWidth
    )
  )
  ctrRam.io.readAddr  := queryIndex
  queryEntry          := ctrRam.io.dataOut
  ctrRam.io.isWrite   := io.updateValid
  ctrRam.io.dataIn    := updateContent
  ctrRam.io.writeAddr := updateIndex
}
