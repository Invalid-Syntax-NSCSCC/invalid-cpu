package pipeline.rob.bundles

import chisel3._
import chisel3.experimental.BundleLiterals._

class RobIdDistributePort(idLength: Int = 32) extends Bundle {
  val writeEn = Input(Bool())
  val id      = Output(UInt(idLength.W))
}
