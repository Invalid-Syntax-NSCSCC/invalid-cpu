package axi

import chisel3._
import chisel3.util._
import spec.Axi.Arb._

class Arbiter(val ports: Int) extends Module {
  val io = IO(new Bundle {
    val request     = Input(UInt(ports.W))
    val acknowledge = Input(UInt(ports.W))

    val grant        = Output(UInt(ports.W))
    val grantValid   = Output(Bool())
    val grantEncoded = Output(UInt(log2Ceil(ports).W))
  })

  val grantReg     = RegInit(0.U(ports.W))
  val grantNextReg = Reg(UInt(ports.W))
  io.grant <> grantReg

  val grantValidReg     = RegInit(false.B)
  val grantValidNextReg = Reg(Bool())
  io.grantValid <> grantValidReg

  val grantEncodedReg     = RegInit(0.U(log2Ceil(ports).W))
  val grantEncodedNextReg = Reg(UInt(log2Ceil(ports).W))
  io.grantEncoded <> grantEncodedReg

  val requestValid = Wire(Bool())
  val requestIndex = Wire(UInt(log2Ceil(ports).W))
  val requestMask  = Wire(UInt(ports.W))

  val priorityEncoderInst = Module(new PriorityEncoder(width = ports))
  priorityEncoderInst.io.inputUnencoded := io.request
  requestValid                          := priorityEncoderInst.io.outputValid
  requestIndex                          := priorityEncoderInst.io.outputEncoded
  requestMask                           := priorityEncoderInst.io.outputUnencoded

  val maskReg  = RegInit(0.U(ports.W))
  val maskNext = Reg(UInt(ports.W))

  val maskedRequestValid = Wire(Bool())
  val maskedRequestIndex = Wire(UInt(log2Ceil(ports).W))
  val maskedRequestMask  = Wire(UInt(ports.W))

  val priorityEncoderMasked = Module(new PriorityEncoder(width = ports))
  priorityEncoderMasked.io.inputUnencoded := io.request & maskReg
  maskedRequestValid                      := priorityEncoderMasked.io.outputValid
  maskedRequestIndex                      := priorityEncoderMasked.io.outputEncoded
  maskedRequestMask                       := priorityEncoderMasked.io.outputUnencoded

  // fallback
  grantNextReg        := 0.U
  grantValidNextReg   := false.B
  grantEncodedNextReg := 0.U
  maskNext            := maskReg
  when((block && !blockAck).B && (grantReg & io.request).orR) {
    // granted request still asserted; hold it
    grantValidNextReg   := grantValidReg
    grantNextReg        := grantReg
    grantEncodedNextReg := grantEncodedReg
  }.elsewhen((block && blockAck).B && (grantValidReg && !(grantReg & io.acknowledge))) {
    // granted request not yet acknowledged; hold it
    grantValidNextReg   := grantValidReg
    grantNextReg        := grantReg
    grantEncodedNextReg := grantEncodedReg
  }.elsewhen(requestValid) {
    if (typeRoundRobin) {
      when(maskedRequestValid) {
        grantValidNextReg   := true.B
        grantNextReg        := maskedRequestMask
        grantEncodedNextReg := maskedRequestIndex
        maskNext := (if (lsbHighPriority) {
                       Fill(ports, true.B) << (maskedRequestIndex + 1.U)
                     } else {
                       Fill(ports, true.B) >> (ports.U - maskedRequestIndex)
                     })
      }.otherwise {
        grantValidNextReg   := true.B
        grantNextReg        := requestMask
        grantEncodedNextReg := requestIndex
        maskNext := (if (lsbHighPriority) {
                       Fill(ports, true.B) << (requestIndex + 1.U)
                     } else {
                       Fill(ports, true.B) >> (ports.U - requestIndex)
                     })
      }
    } else {
      grantValidNextReg   := true.B
      grantNextReg        := requestMask
      grantEncodedNextReg := requestIndex
    }
  }
}
