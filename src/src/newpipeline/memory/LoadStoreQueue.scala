package newpipeline.memory

import chisel3._
import chisel3.util._
import newpipeline.execution.MainExeResultNdPort
import pipeline.memory.AddrTransNdPort

class LoadStoreQueue extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new MainExeResultNdPort))
    val out     = Decoupled(new AddrTransNdPort)
    val isFlush = Input(Bool())
  })

  private val length = 4

  // val queue = Queue
  val storeIn = Flipped(Decoupled(Wire(new AddrTransNdPort)))
  storeIn.bits.gprAddr          := io.in.bits.wb.gprWrite.addr
  storeIn.bits.cacheMaintenance := io.in.bits.cacheMaintenance
  storeIn.bits.instInfo         := io.in.bits.wb.instInfo
  storeIn.bits.isAtomicStore    := io.in.bits.isAtomicStore
  storeIn.bits.memRequest       := io.in.bits.memRequest
  storeIn.bits.tlbMaintenance   := io.in.bits.tlbMaintenance
  storeIn.valid                 := io.in.valid
  io.in.ready                   := storeIn.ready

  val storeOut = Queue(
    storeIn,
    length,
    pipe  = false,
    flow  = false,
    flush = Some(io.isFlush)
  )

  io.out <> storeOut
}
