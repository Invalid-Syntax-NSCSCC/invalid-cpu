package pipeline.dispatch.bundles

import chisel3._
import pipeline.dispatch.RegReadNdPort
import pipeline.rob.bundles.RobReadResultNdPort

class ReservationStationBundle extends Bundle {
  val regReadPort = new RegReadNdPort
  val robResult   = new RobReadResultNdPort
}

object ReservationStationBundle {
  def default = 0.U.asTypeOf(new ReservationStationBundle)
}
