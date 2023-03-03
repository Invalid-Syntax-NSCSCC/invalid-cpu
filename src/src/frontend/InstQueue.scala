package frontend

import chisel3._
import chisel3.util._

import spec._

class InstQueue(val queueLength: Int = 5) extends Module {
  val io = IO(new Bundle {
    val isFlush     = Input(Bool())
    val enqueuePort = Flipped(Decoupled(UInt(Width.inst.W)))
    val dequeuePort = Decoupled(UInt(Width.inst.W))
  })

  val queue = Queue(io.enqueuePort, entries = queueLength, pipe = false, flow = true, flush = Some(io.isFlush))

  io.dequeuePort <> queue
}
