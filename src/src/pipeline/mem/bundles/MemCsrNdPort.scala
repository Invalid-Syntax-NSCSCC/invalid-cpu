package pipeline.mem.bundles

import chisel3._
import control.csrRegsBundles.{CrmdBundle, DmwBundle}

class MemCsrNdPort extends Bundle {
  val crmd = new CrmdBundle
  val dmw  = Vec(2, new DmwBundle)
}
