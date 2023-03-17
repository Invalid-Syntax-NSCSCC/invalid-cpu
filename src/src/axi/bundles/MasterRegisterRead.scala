package axi.bundles

import chisel3._
import chisel3.util._
import spec._

class MasterRegisterRead(val idWidth: Int) extends Bundle {
  val ar = Decoupled(new Bundle {
    val id     = UInt(idWidth.W)
    val addr   = UInt(Width.Axi.addr)
    val len    = UInt(8.W)
    val size   = UInt(3.W)
    val burst  = UInt(2.W)
    val lock   = Bool()
    val cache  = UInt(4.W)
    val prot   = UInt(3.W)
    val qos    = UInt(4.W)
    val region = UInt(4.W)
    val user   = UInt(Width.Axi.aruser)
  })
  val r = Flipped(Decoupled(new Bundle {
    val id   = UInt(idWidth.W)
    val data = UInt(Width.Axi.data)
    val resp = UInt(2.W)
    val last = Bool()
    val user = UInt(Width.Axi.ruser)
  }))
}