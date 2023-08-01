package pmu.bundles

import chisel3._
import chisel3.util._
import spec._

class PmuStoreQueueNdPort extends Bundle {
  val storeOutValid = Bool()
  val storeFull     = Bool()
}
