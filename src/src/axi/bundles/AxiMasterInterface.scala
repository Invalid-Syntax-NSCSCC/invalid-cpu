package axi.bundles

import chisel3._

class AxiMasterInterface extends Bundle {
  val ar = new ArChannel
  val r  = Flipped(new RChannel)
  val aw = new AwChannel
  val w  = new WChannel
  val b  = Flipped(new BChannel)
}
