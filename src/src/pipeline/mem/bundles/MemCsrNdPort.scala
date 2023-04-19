package pipeline.mem.bundles

import chisel3._
import chisel3.util._
import control.csrRegsBundles.{CrmdBundle, DmwBundle}
import spec._

class MemCsrNdPort extends Bundle {
  val crmd = new CrmdBundle
  val dmw  = new DmwBundle
  // TODO
}
