package frontend.bpu.utils
import chisel3._
// simulate BRAM IP in simulation without Vivado
// data read latency is 1 cycle
class Bram(
  dataWidth:     Int = 128,
  dataDepthExp2: Int = 8)
    extends Module {
  // parameter
  val addrWidth = dataDepthExp2
  val dataDepth = 1 << dataDepthExp2
  val io = IO(new Bundle {
    val ena = Input(Bool()) // Chip enable A
    val enb = Input(Bool()) // Chip enable B
    val wea = Input(Bool()) // Write enable A
    val web = Input(Bool()) // Write enable B

    val dina  = Input(UInt(dataWidth.W))
    val addra = Input(UInt(addrWidth.W))
    val douta = Output(UInt(dataWidth.W))

    val dinb  = Input(UInt(dataWidth.W))
    val addrb = Input(UInt(addrWidth.W))
    val doutb = Output(UInt(dataWidth.W))

  })
  val datas = RegInit(VecInit(Seq.fill(dataWidth)(0.U(dataDepth.W))))

  // Read logic
  when(io.ena & io.wea) {
    io.douta := RegNext(io.dina, 0.U)
  }.elsewhen(io.ena) {
    io.douta := RegNext(datas(io.addra), 0.U)
  }.otherwise {
    io.douta := RegNext(0.U(dataDepth.W), 0.U)
  }

  when(io.enb & io.web) {
    io.doutb := RegNext(io.dinb, 0.U)
  }.elsewhen(io.enb) {
    io.doutb := RegNext(datas(io.addrb), 0.U)
  }.otherwise {
    io.doutb := RegNext(0.U(dataDepth.W), 0.U)
  }

  // Write logic
  when(io.enb & io.web) {
    datas(io.addrb) := RegNext(io.dinb, 0.U)
  }

  when(io.ena & io.wea) {
    datas(io.addra) := RegNext(io.dinb, 0.U)
  }
}
