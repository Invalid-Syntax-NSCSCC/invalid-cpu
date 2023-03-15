package axi.bundles

import chisel3._
import chisel3.util._
import spec._

class MasterRegisterWrite(val idWidth: Int) extends Bundle {
  val aw = Decoupled(new Bundle {
    val id     = UInt(idWidth.W)
    val addr   = UInt(Width.Axi.addr.W)
    val len    = UInt(8.W)
    val size   = UInt(3.W)
    val burst  = UInt(2.W)
    val lock   = Bool()
    val cache  = UInt(4.W)
    val prot   = UInt(3.W)
    val qos    = UInt(4.W)
    val region = UInt(4.W)
    val user   = UInt(Width.Axi.awuser.W)
  })
  val w = Decoupled(new Bundle {
    val data = UInt(Width.Axi.data.W)
    val strb = UInt(Width.Axi.strb.W)
    val last = Bool()
    val user = UInt(Width.Axi.wuser.W)
  })
  val b = Flipped(Decoupled(new Bundle {
    val id   = UInt(idWidth.W)
    val resp = UInt(2.W)
    val user = UInt(Width.Axi.buser.W)
  }))
}
