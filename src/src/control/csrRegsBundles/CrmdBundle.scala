package control.csrBundles

import chisel3._
import chisel3.experimental.BundleLiterals._

class CrmdBundle extends Bundle {
  val zero = UInt(23.W)
  val datm = UInt(2.W)
  val datf = UInt(2.W)
  val pg   = Bool() // 映射地址翻译模式使能，高有效
  // 直接地址翻译使能，高有效
  val da  = Bool()
  val ie  = Bool()
  val plv = UInt(2.W)
}

object CrmdBundle {
  def default = (new CrmdBundle).Lit(
    _.zero -> 0.U,
    _.datm -> 0.U,
    _.datf -> 0.U,
    _.pg -> false.B,
    _.da -> true.B, // Attention!: it is '1'
    _.ie -> false.B,
    _.plv -> 0.U
  )
}
