package axi

import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.util._
import spec._

class BetterAxiMaster(
  val readSize:         Int,
  val writeSize:        Int,
  val bytesPerTransfer: Int     = 4,
  val id:               Int,
  isInst:               Boolean = false)
    extends Module {
  val io = IO(new Bundle {
    val axi = new AxiMasterPort
    val write = new Bundle {
      val req = new Bundle {
        val isValid = Input(Bool())
        val isReady = Output(Bool())
        val addr    = Input(UInt(Width.Axi.addr))
        val data    = Input(UInt(writeSize.W))
      }
      val res = new Bundle {
        val isComplete = Output(Bool())
      }
    }
    val read = new Bundle {
      val req = new Bundle {
        val isValid = Input(Bool())
        val isReady = Output(Bool())
        val addr    = Input(UInt(Width.Axi.addr))
      }
      val res = new Bundle {
        val isValid = Output(Bool())
        val data    = Output(UInt(readSize.W))
      }
    }
  })
  // Fallback
  io.axi <> DontCare

  // Only need to use incremental burst
  io.axi.arburst := Value.Axi.Burst.incr
  io.axi.awburst := Value.Axi.Burst.incr

  // Use the largest possible size per transfer (default: 4 bytes [32 bits])
  // Note: Bytes in transfer is 2^AxSIZE
  assert(isPow2(bytesPerTransfer))
  io.axi.arsize := Value.Axi.Size.get(bytesPerTransfer)
  io.axi.awsize := Value.Axi.Size.get(bytesPerTransfer)

  // Set burst number
  // Note: Burst length is AxLEN + 1
  assert(readSize % (bytesPerTransfer * byteLength) == 0)
  assert(writeSize % (bytesPerTransfer * byteLength) == 0)
  val writeLen = WireDefault((writeSize / (bytesPerTransfer * byteLength)).U)
  assert(writeLen <= "b_1111_1111".U)
  io.axi.arlen := ((readSize / (bytesPerTransfer * byteLength)) - 1).U
  io.axi.awlen := writeLen - 1.U

  // Set others
  io.axi.arid    := id.U
  io.axi.awid    := id.U
  io.axi.wid     := id.U
  io.axi.arlock  := Value.Axi.Lock.normal
  io.axi.awlock  := Value.Axi.Lock.normal
  io.axi.arcache := Value.Axi.Cache.nonBufferable
  io.axi.awcache := Value.Axi.Cache.nonBufferable
  io.axi.arprot  := Value.Axi.Protect.get(isPrivileged = true, isSecure = true, isInst = isInst)
  io.axi.awprot  := Value.Axi.Protect.get(isPrivileged = true, isSecure = true, isInst = isInst)
  io.axi.wstrb   := "b_1111".U(Width.Axi.strb) // TODO: Support other write masks
  io.axi.wvalid  := false.B
  io.axi.rready  := false.B
  io.axi.bready  := false.B

  // Size per transfer in bits
  val transferSize = bytesPerTransfer * 8

  // Handle read

  val isReadingReg = RegInit(false.B)
  isReadingReg := isReadingReg // Fallback: Keep state
  val readDataReg  = RegInit(0.U(readSize.W))
  val nextReadData = WireDefault(readDataReg)
  readDataReg := nextReadData // Fallback: Keep data
  val isReadReady = WireDefault(!isReadingReg && io.axi.arready)

  io.axi.araddr       := io.read.req.addr
  io.axi.arvalid      := io.read.req.isValid
  io.read.req.isReady := isReadReady

  // Fallback
  io.read.res.isValid := false.B
  io.read.res.data    := nextReadData

  when(io.read.req.isValid && isReadReady) {
    // Accept request
    isReadingReg := true.B
    nextReadData := 0.U
  }

  when(isReadingReg) {
    // Reading
    io.axi.rready := true.B
    when(io.axi.rvalid) {
      switch(io.axi.rresp) {
        is(Value.Axi.Response.Okay, Value.Axi.Response.ExclusiveOkay) {
          nextReadData := Cat(
            io.axi.rdata(transferSize - 1, 0),
            readDataReg(readDataReg.getWidth - 1, transferSize)
          )
        }

        is(Value.Axi.Response.SlaveErr, Value.Axi.Response.DecodeErr) {
          nextReadData := Cat(
            0.U(transferSize.W),
            readDataReg(readDataReg.getWidth - 1, transferSize)
          )
        }
      }
    }
    when(io.axi.rlast) {
      // Reading complete
      io.read.res.isValid := true.B
      io.read.res.data    := nextReadData

      isReadingReg := false.B
    }
  }

  // Handle write

  val isWritingReg = RegInit(false.B)
  isWritingReg := isWritingReg // Fallback: Keep state
  val writeDataReg = RegInit(0.U(writeSize.W))
  writeDataReg := writeDataReg // Fallback: Keep data
  val isWriteReady    = WireDefault(!isWritingReg && io.axi.awready)
  val writeCountUpReg = RegInit(writeLen)
  writeCountUpReg := writeCountUpReg // Fallback: Keep number

  io.axi.awaddr        := io.write.req.addr
  io.axi.awvalid       := io.write.req.isValid
  io.write.req.isReady := !isWritingReg && io.axi.awready

  // Fallback
  io.write.res.isComplete := false.B

  when(io.write.req.isValid && isWriteReady) {
    // Accept request
    isWritingReg    := true.B
    writeDataReg    := io.write.req.data
    writeCountUpReg := 0.U
  }

  when(isWritingReg) {
    // Writing
    io.axi.bready := true.B
    io.axi.wvalid := true.B
    io.axi.wdata  := writeDataReg(transferSize - 1, 0)

    when(io.axi.wready) {
      // Next cycle write the next data
      writeCountUpReg := writeCountUpReg + 1.U
      writeDataReg    := Cat(0.U(transferSize.W), writeDataReg(writeDataReg.getWidth - 1, transferSize))
    }

    // The last transfer
    when(writeCountUpReg === (writeLen - 1.U)) {
      io.axi.wlast := true.B
    }

    // No write data after the last transfer
    when(writeCountUpReg === writeLen) {
      io.axi.wvalid := false.B
    }

    // Complete transfer
    when(io.axi.bvalid) {
      switch(io.axi.bresp) {
        is(
          Value.Axi.Response.Okay,
          Value.Axi.Response.ExclusiveOkay,
          Value.Axi.Response.SlaveErr,
          Value.Axi.Response.DecodeErr
        ) {
          // Writing complete
          io.write.res.isComplete := true.B

          isWritingReg := false.B
        }
      }
    }
  }
}
