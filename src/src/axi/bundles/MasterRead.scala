package axi.bundles

import chisel3._
import chisel3.util._
import spec._

class MasterRead extends Bundle {
  val ar = Decoupled(new Bundle {
    val id     = UInt(Param.Width.Axi.masterId)
    val addr   = UInt(Width.Axi.addr)
    val len    = UInt(8.W)
    val size   = UInt(3.W)
    val burst  = UInt(2.W)
    val lock   = Bool()
    val cache  = UInt(4.W)
    val prot   = UInt(3.W)
    val qos    = UInt(4.W)
    val region = UInt(4.W)
    val user   = UInt(Param.Width.Axi.aruser)
  })
  val r = Flipped(Decoupled(new Bundle {
    val id   = UInt(Param.Width.Axi.masterId)
    val data = UInt(Param.Width.Axi.data)
    val resp = UInt(2.W)
    val last = Bool()
    val user = UInt(Param.Width.Axi.ruser)
  }))
}
