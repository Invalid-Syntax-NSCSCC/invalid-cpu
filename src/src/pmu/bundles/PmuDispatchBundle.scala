package pmu.bundles

import spec._
import chisel3._

class PmuDispatchBundle extends Bundle {
  val bubbleFromBackend        = Bool()
  val bubbleFromDataDependence = Bool()
  val bubbleFromRSEmpty        = Bool()
  val isFull                   = Bool()
}
