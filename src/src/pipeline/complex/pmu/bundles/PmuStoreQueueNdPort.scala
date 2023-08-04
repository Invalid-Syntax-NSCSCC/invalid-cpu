package pipeline.complex.pmu.bundles

import chisel3._

class PmuStoreQueueNdPort extends Bundle {
  val storeOutValid = Bool()
  val storeFull     = Bool()
}
