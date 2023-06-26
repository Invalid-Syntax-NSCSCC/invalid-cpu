package axi.bundles

import chisel3._

class SlaveCrossbar extends Bundle {
  val read  = new SlaveRead
  val write = new SlaveWrite
}
