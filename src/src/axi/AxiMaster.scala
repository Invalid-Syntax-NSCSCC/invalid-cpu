package axi

import axi.bundles.AxiMasterInterface
import chisel3._
import spec._

class AxiMaster(val Id: Int = 0) extends Module {
  val io = IO(new Bundle {
    val axi        = new AxiMasterInterface
    val newRequest = Input(Bool())
    val we         = Input(Bool())
    val uncached   = Input(Bool())
    val addr       = Input(UInt(spec.Width.Axi.addr))
    val size       = Input(UInt(3.W))
    val dataIn     = Input(UInt(Param.Width.Axi.data))
    val wstrb      = Input(UInt(Param.Width.Axi.strb))

    val readyOut = Output(Bool())
    val validOut = Output(Bool())
    val dataOut  = Output(UInt(Param.Width.Axi.data))
  })

  io <> DontCare

  // AXI ID
  io.axi.ar.bits.id := Id.U
  io.axi.aw.bits.id := Id.U

  // read constants
  io.axi.ar.bits.len   := 0.U // 1 request
  io.axi.ar.bits.burst := 0.U // burst type does not matter
  io.axi.r.ready       := true.B // always ready to receive data

  val addrReg   = RegNext(io.addr)
  val sizeReg   = RegNext(io.size)
  val dataInReg = RegNext(io.dataIn)
  val wstrbReg  = RegNext(io.wstrb)
  val cacheReg  = Reg(UInt(4.W))
  when(io.uncached) {
    cacheReg := "b0000".U
  }.otherwise {
    cacheReg := "b1111".U
  }
  when(io.newRequest) {
    io.axi.ar.bits.addr  := addrReg
    io.axi.ar.bits.size  := sizeReg
    io.axi.aw.bits.size  := sizeReg
    io.axi.aw.bits.addr  := addrReg
    io.axi.w.bits.data   := dataInReg
    io.axi.w.bits.strb   := wstrbReg
    io.axi.ar.bits.cache := cacheReg
    io.axi.aw.bits.cache := cacheReg
  }

  // write constants
  io.axi.aw.bits.len   := 0.U
  io.axi.aw.bits.burst := 0.U
  io.axi.b.ready       := true.B

  val readyMod = Module(new SetClrReg(setOverClr = false, width = 1, resetValue = 1))
  readyMod.io.set := (io.axi.r.valid && (io.axi.r.bits.id === Id.U)) || (io.axi.b.valid && (io.axi.b.bits.id === Id.U))
  readyMod.io.clr := io.newRequest
  io.readyOut     := readyMod.io.result

  val validReg = RegNext(io.axi.r.valid, 0.U)
  io.validOut := validReg

  // read channel
  val arvalidMod = Module(new SetClrReg(setOverClr = true, width = 1, resetValue = 0))
  arvalidMod.io.set := io.newRequest && !io.we
  arvalidMod.io.clr := io.axi.ar.ready
  io.axi.ar.valid   := arvalidMod.io.result

  val rdataReg = RegNext(io.axi.r.bits.data)
  when(io.axi.r.valid) {
    io.dataOut := rdataReg
  }

  val awvalidMod = Module(new SetClrReg(setOverClr = true, width = 1, resetValue = 0))
  awvalidMod.io.set := io.newRequest & io.we
  awvalidMod.io.clr := io.axi.aw.ready
  io.axi.aw.valid   := awvalidMod.io.result

  val wvalidMod = Module(new SetClrReg(setOverClr = true, width = 1, resetValue = 0))
  wvalidMod.io.set := io.newRequest & io.we
  wvalidMod.io.clr := io.axi.w.ready
  io.axi.w.valid   := wvalidMod.io.result

  io.axi.w.bits.last := io.axi.w.valid
}
