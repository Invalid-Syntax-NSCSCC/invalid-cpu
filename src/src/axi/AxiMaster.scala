package axi

import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util.RegEnable
import spec._

class AxiMaster(val Id: Int = 0) extends Module {
  val io = IO(new Bundle {
    val axi        = new AxiMasterInterface
    val newRequest = Input(Bool())
    val we         = Input(Bool())
    val uncached   = Input(Bool())
    val addr       = Input(UInt(Width.Axi.addr))
    val size       = Input(UInt(3.W))
    val dataIn     = Input(UInt(Width.Axi.data))
    val wstrb      = Input(UInt(Width.Axi.strb))

    val readyOut = Output(Bool())
    val validOut = Output(Bool())
    val dataOut  = Output(UInt(Width.Axi.data))
  })

  io <> DontCare

  // AXI ID
  io.axi.ar.bits.id := Id.U
  io.axi.aw.bits.id := Id.U

  // read constants
  io.axi.ar.bits.len   := 0.U // 1 request
  io.axi.ar.bits.burst := 0.U // burst type does not matter
  io.axi.r.ready       := true.B // always ready to receive data

  val addrReg   = RegEnable(io.addr, 0.U(Width.Axi.addr), io.newRequest)
  val sizeReg   = RegEnable(io.size, 0.U(3.W), io.newRequest)
  val dataInReg = RegEnable(io.dataIn, 0.U(Width.Axi.data), io.newRequest)
  val wstrbReg  = RegEnable(io.wstrb, 0.U(Width.Axi.strb), io.newRequest)
  val cacheReg  = Reg(UInt(4.W))
  when(io.uncached) {
    cacheReg := "b0000".U
  }.otherwise {
    cacheReg := "b1111".U
  }
  io.axi.ar.bits.addr  := addrReg
  io.axi.ar.bits.size  := sizeReg
  io.axi.aw.bits.size  := sizeReg
  io.axi.aw.bits.addr  := addrReg
  io.axi.w.bits.data   := dataInReg
  io.axi.w.bits.strb   := wstrbReg
  io.axi.ar.bits.cache := cacheReg
  io.axi.aw.bits.cache := cacheReg
  when(io.newRequest) {
    io.axi.ar.bits.addr := io.addr
    io.axi.ar.bits.size := io.size
    io.axi.aw.bits.size := io.size
    io.axi.aw.bits.addr := io.addr
    io.axi.w.bits.data  := io.dataIn
    io.axi.w.bits.strb  := io.wstrb
  }

  // write constants
  io.axi.aw.bits.len   := 0.U
  io.axi.aw.bits.burst := 0.U
  io.axi.b.ready       := true.B

  val readyMod = Module(new SetClrReg(setOverClr = false, width = 1, resetValue = 1))
  readyMod.io.set := (io.axi.r.valid && (io.axi.r.bits.id === Id.U)) || (io.axi.b.valid && (io.axi.b.bits.id === Id.U))
  readyMod.io.clr := io.newRequest
  io.readyOut     := readyMod.io.result

  val validReg = RegNext(io.axi.r.valid || io.axi.b.valid, false.B)
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
