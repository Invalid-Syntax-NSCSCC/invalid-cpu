package control.bundles

import chisel3._
import spec._

class CuToCsrNdPort extends Bundle {
  val exceptionFlush = Bool()
  val ertnFlush      = Bool()
  val era            = UInt(Width.Reg.data)
  val ecodeBundle    = new EcodeBundle
  // tlb重填失效
  val tlbRefillException = Bool()
  // 出错虚地址
  val badVAddrSet = new BadVAddrSetBundle
  // llbit设置
  val llbitSet = new LLBitSetBundle
  // 外部中断
  val hardwareInterrupt = Input(UInt(8.W))
}

object CuToCsrNdPort {
  def default = 0.U.asTypeOf(new CuToCsrNdPort)
}
