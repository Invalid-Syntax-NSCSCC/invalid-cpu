package frontend

import axi.AxiMaster
import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.util._
import pipeline.dispatch.bundles.InstInfoBundle
import spec._

class SimpleFetchStage extends Module {
  val io = IO(new Bundle {
    val pc                 = Input(UInt(Width.Reg.data))
    val axiMasterInterface = Flipped(new AxiMasterPort)
    val instEnqueuePort    = Decoupled(new InstInfoBundle)
  })

  val axiMaster = Module(new AxiMaster)
  axiMaster.io.axi <> io.axiMasterInterface
  axiMaster.io.we       := false.B
  axiMaster.io.uncached := true.B
  axiMaster.io.size     := 4.U
  axiMaster.io.dataIn   := 0.U
  axiMaster.io.wstrb    := 0.U
}
