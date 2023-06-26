package frontend.bundles

import chisel3._
import control.csrBundles.{CrmdBundle, DmwBundle}

class FetchCsrNdPort extends Bundle {
  val crmd = new CrmdBundle
  val dmw  = Vec(2, new DmwBundle)
}
