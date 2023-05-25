package axi

import axi.bundles.AxiMasterInterface
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
    val axi = new AxiMasterInterface
    val write = new Bundle {
      val req = new Bundle {
        val isValid = Input(Bool())
        val isReady = Output(Bool())
        val addr    = Input(UInt(spec.Width.Axi.addr))
        val data    = Input(UInt(writeSize.W))
        val mask    = Input(UInt(Param.Width.Axi.strb))
      }
      val res = new Bundle {
        val isComplete = Output(Bool())
        val isFailed   = Output(Bool())
      }
    }
    val read = new Bundle {
      val req = new Bundle {
        val isValid = Input(Bool())
        val isReady = Output(Bool())
        val addr    = Input(UInt(spec.Width.Axi.addr))
      }
      val res = new Bundle {
        val isValid  = Output(Bool())
        val data     = Output(UInt(readSize.W))
        val isFailed = Output(Bool())
      }
    }
  })

  // Fallback
  io.axi <> DontCare

  // Only need to use incremental burst
  io.axi.ar.bits.burst := Value.Axi.Burst.incr
  io.axi.aw.bits.burst := Value.Axi.Burst.incr

  // Use the largest possible size per transfer (default: 4 bytes [32 bits])
  // Note: Bytes in transfer is 2^AxSIZE
  assert(isPow2(bytesPerTransfer))
  io.axi.ar.bits.size := Value.Axi.Size.get(bytesPerTransfer)
  io.axi.aw.bits.size := Value.Axi.Size.get(bytesPerTransfer)

  // Set burst number
  // Note: Burst length is AxLEN + 1
  assert(readSize % (bytesPerTransfer * byteLength) == 0)
  assert(writeSize % (bytesPerTransfer * byteLength) == 0)
  val writeLen = WireDefault((writeSize / (bytesPerTransfer * byteLength)).U)
  assert(writeLen <= "b_1111_1111".U)
  io.axi.ar.bits.len := ((readSize / (bytesPerTransfer * byteLength)) - 1).U
  io.axi.aw.bits.len := writeLen - 1.U

  // Set others
  io.axi.ar.bits.id    := id.U
  io.axi.aw.bits.id    := id.U
  io.axi.ar.bits.lock  := Value.Axi.Lock.normal
  io.axi.aw.bits.lock  := Value.Axi.Lock.normal
  io.axi.ar.bits.cache := Value.Axi.Cache.nonBufferable
  io.axi.aw.bits.cache := Value.Axi.Cache.nonBufferable
  io.axi.ar.bits.prot  := Value.Axi.Protect.get(isPrivileged = true, isSecure = true, isInst = isInst)
  io.axi.aw.bits.prot  := Value.Axi.Protect.get(isPrivileged = true, isSecure = true, isInst = isInst)
  io.axi.w.bits.strb   := io.write.req.mask
  io.axi.w.valid       := false.B
  io.axi.r.ready       := false.B
  io.axi.b.ready       := false.B

  // Size per transfer in bits
  val transferSize = bytesPerTransfer * 8

  // Handle read

  val isReadingReg = RegInit(false.B)
  isReadingReg := isReadingReg // Fallback: Keep state
  val readDataReg  = RegInit(0.U(readSize.W))
  val nextReadData = WireDefault(readDataReg)
  readDataReg := nextReadData // Fallback: Keep data
  val isReadReady = WireDefault(!isReadingReg && io.axi.ar.ready)

  // Failing flag
  val isReadFailedReg  = RegInit(false.B)
  val isReadFailedNext = WireDefault(isReadFailedReg)
  isReadFailedReg      := isReadFailedNext // Fallback: Keep flag
  io.read.res.isFailed := false.B // Fallback: Not failed

  io.axi.ar.bits.addr := io.read.req.addr
  io.axi.ar.valid     := io.read.req.isValid
  io.read.req.isReady := isReadReady

  // Fallback
  io.read.res.isValid := false.B
  io.read.res.data    := nextReadData

  when(io.read.req.isValid && isReadReady) {
    // Accept request
    isReadFailedNext := false.B
    isReadingReg     := true.B
    nextReadData     := 0.U
  }

  when(isReadingReg) {
    // Reading
    io.axi.r.ready := true.B
    when(io.axi.r.valid) {
      switch(io.axi.r.bits.resp) {
        is(Value.Axi.Response.Okay, Value.Axi.Response.ExclusiveOkay) {
          nextReadData := Cat(
            io.axi.r.bits.data(transferSize - 1, 0),
            readDataReg(readDataReg.getWidth - 1, transferSize)
          )
        }

        is(Value.Axi.Response.SlaveErr, Value.Axi.Response.DecodeErr) {
          isReadFailedNext := true.B
          nextReadData := Cat(
            0.U(transferSize.W),
            readDataReg(readDataReg.getWidth - 1, transferSize)
          )
        }
      }
    }
    when(io.axi.r.bits.last) {
      // Reading complete
      io.read.res.isValid  := true.B
      io.read.res.data     := nextReadData
      io.read.res.isFailed := isReadFailedReg || isReadFailedNext

      isReadingReg := false.B
    }
  }

  // Handle write

  val isWritingReg = RegInit(false.B)
  isWritingReg := isWritingReg // Fallback: Keep state
  val writeDataReg = RegInit(0.U(writeSize.W))
  writeDataReg := writeDataReg // Fallback: Keep data
  val isWriteReady    = WireDefault(!isWritingReg && io.axi.aw.ready)
  val writeCountUpReg = RegInit(writeLen)
  writeCountUpReg := writeCountUpReg // Fallback: Keep number

  io.axi.aw.bits.addr  := io.write.req.addr
  io.axi.aw.valid      := io.write.req.isValid
  io.write.req.isReady := !isWritingReg && io.axi.aw.ready

  // Fallback
  io.write.res.isComplete := false.B
  io.write.res.isFailed   := false.B

  when(io.write.req.isValid && isWriteReady) {
    // Accept request
    isWritingReg    := true.B
    writeDataReg    := io.write.req.data
    writeCountUpReg := 0.U
  }

  when(isWritingReg) {
    // Writing
    io.axi.b.ready     := true.B
    io.axi.w.valid     := true.B
    io.axi.w.bits.data := writeDataReg(transferSize - 1, 0)

    when(io.axi.w.ready) {
      // Next cycle write the next data
      writeCountUpReg := writeCountUpReg + 1.U
      writeDataReg    := Cat(0.U(transferSize.W), writeDataReg(writeDataReg.getWidth - 1, transferSize))
    }

    // The last transfer
    when(writeCountUpReg === (writeLen - 1.U)) {
      io.axi.w.bits.last := true.B
    }

    // No write data after the last transfer
    when(writeCountUpReg === writeLen) {
      io.axi.w.valid := false.B
    }

    // Complete transfer
    when(io.axi.b.valid) {
      switch(io.axi.b.bits.resp) {
        is(
          Value.Axi.Response.Okay,
          Value.Axi.Response.ExclusiveOkay
        ) {
          // Writing complete
          io.write.res.isComplete := true.B

          isWritingReg := false.B
        }
        is(
          Value.Axi.Response.SlaveErr,
          Value.Axi.Response.DecodeErr
        ) {
          // Writing complete
          io.write.res.isComplete := true.B
          io.write.res.isFailed   := true.B

          isWritingReg := false.B
        }
      }
    }
  }
}
