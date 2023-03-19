package axi.bundles

import chisel3._
import chisel3.util._
import spec._

class SlaveCrossbar extends Bundle {
  val read = new SlaveRead
  val write = new SlaveWrite
}
