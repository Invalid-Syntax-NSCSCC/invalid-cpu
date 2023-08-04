package pipeline.simple.pmu.bundles

import chisel3._

class PmuDispatchInfoBundle extends Bundle {
  val bubbleFromBackend        = Bool()
  val bubbleFromFrontend       = Bool()
  val bubbleFromDataDependence = Bool()
  val isIssueInst              = Bool()
}
