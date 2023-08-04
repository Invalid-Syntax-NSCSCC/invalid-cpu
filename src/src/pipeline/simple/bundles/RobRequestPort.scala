package pipeline.simple.bundles

import chisel3._
import chisel3.util._
import pipeline.common.bundles.PcInstBundle
import spec._

class RobRequestPort extends Bundle {
  val request = Input(Valid(new PcInstBundle))
  val result  = Output(Valid(UInt(Param.Width.Rob.id)))
}
