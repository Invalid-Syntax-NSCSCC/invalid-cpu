package memory

import chisel3._
import chisel3.util._

class VSingleBRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite = Input(Bool())
    val addr    = Input(UInt(addrWidth.W))
    val dataIn  = Input(UInt(dataWidth.W))
    val dataOut = Output(UInt(dataWidth.W))
  })

  val blackBox = Module(new single_readfirst_bram(size, dataWidth))

  blackBox.io.addra  := io.addr
  blackBox.io.dina   := io.dataIn
  blackBox.io.clka   := clock
  blackBox.io.wea    := io.isWrite
  blackBox.io.ena    := true.B
  blackBox.io.rsta   := reset
  blackBox.io.regcea := false.B
  io.dataOut         := blackBox.io.douta
}

class VTrueDualBRam(size: Int, dataWidth: Int) extends Module {
  // TODO: customize it as you want
  val io = IO(new Bundle {})

  val blackBox = Module(new truedual_readfirst_bram(size, dataWidth))

  blackBox.io.addra  := DontCare
  blackBox.io.addrb  := DontCare
  blackBox.io.dina   := DontCare
  blackBox.io.dinb   := DontCare
  blackBox.io.clka   := DontCare
  blackBox.io.wea    := DontCare
  blackBox.io.web    := DontCare
  blackBox.io.ena    := DontCare
  blackBox.io.enb    := DontCare
  blackBox.io.rsta   := DontCare
  blackBox.io.rstb   := DontCare
  blackBox.io.regcea := DontCare
  blackBox.io.regceb := DontCare
  DontCare           <> blackBox.io.douta
  DontCare           <> blackBox.io.doutb
}

class VSimpleDualBRam(size: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(size)

  val io = IO(new Bundle {
    val isWrite   = Input(Bool())
    val readAddr  = Input(UInt(addrWidth.W))
    val writeAddr = Input(UInt(addrWidth.W))
    val dataIn    = Input(UInt(dataWidth.W))
    val dataOut   = Output(UInt(dataWidth.W))
  })

  val blackBox = Module(new simpledual_readfirst_bram(size, dataWidth))

  blackBox.io.addra  := io.readAddr
  blackBox.io.addrb  := io.writeAddr
  blackBox.io.dina   := io.dataIn
  blackBox.io.clka   := clock
  blackBox.io.wea    := io.isWrite
  blackBox.io.enb    := true.B
  blackBox.io.rstb   := reset
  blackBox.io.regceb := false.B
  io.dataOut         := blackBox.io.doutb
}

class single_readfirst_bram(size: Int, dataWidth: Int)
    extends BlackBox(
      Map(
        "RAM_WIDTH" -> dataWidth,
        "RAM_DEPTH" -> size,
        "RAM_PERFORMANCE" -> "LOW_LATENCY"
      )
    ) {
  val io = IO(new Bundle {
    val addra  = Input(UInt(log2Ceil(size).W))
    val dina   = Input(UInt(dataWidth.W))
    val clka   = Input(Clock())
    val wea    = Input(Bool())
    val ena    = Input(Bool())
    val rsta   = Input(Bool())
    val regcea = Input(Bool())
    val douta  = Output(UInt(dataWidth.W))
  })
}

class truedual_readfirst_bram(size: Int, dataWidth: Int)
    extends BlackBox(
      Map(
        "RAM_WIDTH" -> dataWidth,
        "RAM_DEPTH" -> size,
        "RAM_PERFORMANCE" -> "LOW_LATENCY"
      )
    ) {
  val io = IO(new Bundle {
    val addra  = Input(UInt(log2Ceil(size).W))
    val addrb  = Input(UInt(log2Ceil(size).W))
    val dina   = Input(UInt(dataWidth.W))
    val dinb   = Input(UInt(dataWidth.W))
    val clka   = Input(Clock())
    val wea    = Input(Bool())
    val web    = Input(Bool())
    val ena    = Input(Bool())
    val enb    = Input(Bool())
    val rsta   = Input(Bool())
    val rstb   = Input(Bool())
    val regcea = Input(Bool())
    val regceb = Input(Bool())
    val douta  = Output(UInt(dataWidth.W))
    val doutb  = Output(UInt(dataWidth.W))
  })
}

class simpledual_readfirst_bram(size: Int, dataWidth: Int)
    extends BlackBox(
      Map(
        "RAM_WIDTH" -> dataWidth,
        "RAM_DEPTH" -> size,
        "RAM_PERFORMANCE" -> "LOW_LATENCY"
      )
    ) {
  val io = IO(new Bundle {
    val addra  = Input(UInt(log2Ceil(size).W))
    val addrb  = Input(UInt(log2Ceil(size).W))
    val dina   = Input(UInt(dataWidth.W))
    val clka   = Input(Clock())
    val wea    = Input(Bool())
    val enb    = Input(Bool())
    val rstb   = Input(Bool())
    val regceb = Input(Bool())
    val doutb  = Output(UInt(dataWidth.W))
  })
}
