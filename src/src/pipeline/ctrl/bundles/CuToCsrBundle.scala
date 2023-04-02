package pipeline.ctrl.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._

class CuToCsrNdPort extends Bundle {
  val exceptionFlush = Bool()
  val etrnFlush      = Bool()
  val era            = UInt(Width.Reg.data)
  val ecodeBunle     = new EcodeBundle
  // tlb重填失效
  val tlbRefillException = Bool()
  // 出错虚地址
  val badVAddrSet = new BadVAddrSetBundle
  // llbit设置
  val llbitSet = new LLBitSetBundle
}

object CuToCsrNdPort {
  val default = 0.U.asTypeOf(new CuToCsrNdPort)
}
