package axi

import chisel3._
import common.bundles.AxiMasterPort
import spec._

class AxiMaster(val Id: Int = 0) extends Module {
  val io = IO(new Bundle {
    val axi        = new AxiMasterPort
    val newRequest = Input(Bool())
    val we         = Input(Bool())
    val uncached   = Input(Bool())
    val addr       = Input(UInt(Width.Axi.addr.W))
    val size       = Input(UInt(3.W))
    val dataIn     = Input(UInt(Width.Axi.data.W))
    val wstrb      = Input(UInt(Width.Axi.strb.W))

    val readyOut = Output(Bool())
    val validOut = Output(Bool())
    val dataOut  = Output(UInt(Width.Axi.data.W))
  })

  // AXI ID
  io.axi.arid := Id.U
  io.axi.awid := Id.U

  // read constants
  io.axi.arlen   := 0.U // 1 request
  io.axi.arburst := 0.U // burst type does not matter
  io.axi.rready  := 1.U // always ready to receive data

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
    io.axi.araddr  := addrReg
    io.axi.arsize  := sizeReg
    io.axi.awsize  := sizeReg
    io.axi.awaddr  := addrReg
    io.axi.wdata   := dataInReg
    io.axi.wstrb   := wstrbReg
    io.axi.arcache := cacheReg
    io.axi.awcache := cacheReg
  }

  // write constants
  io.axi.awlen   := 0.U
  io.axi.awburst := 0.U
  io.axi.bready  := true.B

  val validReg = RegNext(io.axi.rvalid, 0.U)
  io.validOut := validReg

  // read channel
  val arvalidMod = Module(new SetClrReg(setOverClr = true, width = 1, resetValue = 0))
  arvalidMod.io.set := io.newRequest & ~io.we
  arvalidMod.io.clr := io.axi.arready
  io.axi.arvalid    := arvalidMod.io.result

  val rdataReg = RegNext(io.axi.rdata)
  when(io.axi.rvalid) {
    io.dataOut := rdataReg
  }

  val awvalidMod = Module(new SetClrReg(setOverClr = true, width = 1, resetValue = 0))
  awvalidMod.io.set := io.newRequest & io.we
  awvalidMod.io.clr := io.axi.awready
  io.axi.awvalid    := awvalidMod.io.result

  val wvalidMod = Module(new SetClrReg(setOverClr = true, width = 1, resetValue = 0))
  wvalidMod.io.set := io.newRequest & io.we
  wvalidMod.io.clr := io.axi.wready
  io.axi.wvalid    := wvalidMod.io.result

  io.axi.wlast := io.axi.wvalid
}
