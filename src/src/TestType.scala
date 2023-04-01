import chisel3._
import chisel3.util._
import spec.Param

class TestType extends Module {
  val io = IO(new Bundle {
    val in   = Input(UInt(8.W))
    val out  = Output(UInt(8.W))
    val pout = Output(UInt(4.W))
  })

  class BundlePassPort[T <: Bundle](port: T) extends Bundle {
    val in  = Wire(port)
    val out = Wire(port)
  }

  def modifyUInt[T <: Bundle](u: UInt, bun: T): BundlePassPort[T] = {
    val passPort = new BundlePassPort(bun)
    u            := passPort.in.asUInt
    passPort.out := u.asTypeOf(bun)
    passPort
  }

  val reg = RegInit(0.U(8.W))
  io.out := reg

  val regp = modifyUInt(
    reg,
    new Bundle {
      val up   = UInt(2.W)
      val mid  = UInt(4.W)
      val down = UInt(2.W)
    }
  )

  regp.in.up   := regp.out.up
  regp.in.mid  := io.in(3, 0)
  regp.in.down := 1.U(2.W)

  io.pout := PriorityEncoder(io.in(2, 0))
}
