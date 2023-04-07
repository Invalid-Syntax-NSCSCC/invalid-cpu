package axi

import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.util._
import spec._

class BetterAxiMaster(
  val readSize:        Int,
  val writeSize:       Int,
  val sizePerTransfer: Int     = 4,
  val Id:              Int,
  isInst:              Boolean = false)
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

  // Only need to use incremental burst
  io.axi.arburst := Value.Axi.Burst.incr
  io.axi.awburst := Value.Axi.Burst.incr

  // Use the largest possible size per transfer (default: 4 bytes [32 bits])
  // Note: Bytes in transfer is 2^AxSIZE
  assert((sizePerTransfer != 0) && ((sizePerTransfer & (sizePerTransfer - 1)) == 0)) // Check if is power of 2
  io.axi.arsize := Value.Axi.Size.get(sizePerTransfer)
  io.axi.awsize := Value.Axi.Size.get(sizePerTransfer)

  // Set burst number
  // Note: Burst length is AxLEN + 1
  assert(readSize % sizePerTransfer == 0)
  assert(writeSize % sizePerTransfer == 0)
  val writeLen = WireDefault((writeSize / sizePerTransfer).U)
  assert(writeLen <= "b_1111_1111".U)
  io.axi.arlen := ((readSize / sizePerTransfer) - 1).U
  io.axi.awlen := writeLen - 1.U

  // Set others
  io.axi.arid    := Id.U
  io.axi.awid    := Id.U
  io.axi.arlock  := Value.Axi.Lock.normal
  io.axi.awlock  := Value.Axi.Lock.normal
  io.axi.arcache := Value.Axi.Cache.nonBufferable
  io.axi.awcache := Value.Axi.Cache.nonBufferable
  io.axi.arprot  := Value.Axi.Protect.get(isPrivileged = true, isSecure = true, isInst = isInst)
  io.axi.awprot  := Value.Axi.Protect.get(isPrivileged = true, isSecure = true, isInst = isInst)

  // Size per transfer in bits
  val transferSize = sizePerTransfer * 8

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
          nextReadData := Cat(readDataReg(readDataReg.getWidth - transferSize - 1, 0), io.axi.rdata)
        }

        is(Value.Axi.Response.SlaveErr, Value.Axi.Response.DecodeErr) {
          nextReadData := Cat(readDataReg(readDataReg.getWidth - transferSize - 1, 0), 0.U(transferSize.W))
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
    io.axi.wdata  := writeDataReg(writeSize - 1, 0)

    when(io.axi.wready) {
      // Next cycle write the next data
      writeCountUpReg := writeCountUpReg + 1.U
      writeDataReg    := Cat(0.U(writeSize.U), writeDataReg(writeDataReg.getWidth - 1, writeSize))
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
