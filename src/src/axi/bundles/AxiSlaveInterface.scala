package axi.bundles

import chisel3._

class AxiSlaveInterface extends Bundle {
  val ar = Flipped(new ArChannel)
  val r  = new RChannel
  val aw = Flipped(new AwChannel)
  val w  = Flipped(new WChannel)
  val b  = new BChannel
}
