package axi.bundles

import chisel3._

class MasterCrossbar extends Bundle {
  val read  = new MasterRead
  val write = new MasterWrite
}
