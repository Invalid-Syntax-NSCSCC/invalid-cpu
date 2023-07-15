package pipeline.common

import chisel3._
import chisel3.util._

class LookupQueue[T <: Data, LUIn <: Data](
  val gen:             T,
  val entries:         Int,
  val lookupInFactory: LUIn,
  val lookupNeqFn:     (LUIn, T) => Bool,
  val pipe:            Boolean = false,
  val flow:            Boolean = false,
  val hasFlush:        Boolean = false)
    extends Module() {
  require(entries > -1, "Queue must have non-negative number of entries")
  require(entries != 0, "Use companion object Queue.apply for zero entries")
  val genType  = gen
  val luInType = lookupInFactory

  val io = IO(new Bundle {
    val queue = new QueueIO(genType, entries, hasFlush)
    val lookup = new Bundle {
      val in  = Input(luInType)
      val out = Output(Bool())
    }
  })
  val ram         = Reg(Vec(entries, genType))
  val valid_flags = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val enq_ptr     = Counter(entries)
  val deq_ptr     = Counter(entries)
  val maybe_full  = RegInit(false.B)
  val ptr_match   = enq_ptr.value === deq_ptr.value
  val empty       = ptr_match && !maybe_full
  val full        = ptr_match && maybe_full
  val do_enq      = WireDefault(io.queue.enq.fire)
  val do_deq      = WireDefault(io.queue.deq.fire)
  val flush       = io.queue.flush.getOrElse(false.B)

  valid_flags := valid_flags

  // Lookup
  val isNotHit = VecInit(valid_flags.zip(ram).map {
    case (valid, entry) =>
      !valid || lookupNeqFn(io.lookup.in, entry)
  }).asUInt.orR
  io.lookup.out := isNotHit

  // when flush is high, empty the queue
  // Semantically, any enqueues happen before the flush.
  when(do_enq) {
    ram(enq_ptr.value)         := io.queue.enq.bits
    valid_flags(enq_ptr.value) := true.B
    enq_ptr.inc()
  }
  when(do_deq) {
    valid_flags(deq_ptr.value) := false.B
    deq_ptr.inc()
  }
  when(do_enq =/= do_deq) {
    maybe_full := do_enq
  }
  when(flush) {
    enq_ptr.reset()
    deq_ptr.reset()
    maybe_full := false.B
    valid_flags.foreach(_ := false.B)
  }

  io.queue.deq.valid := !empty
  io.queue.enq.ready := !full

  io.queue.deq.bits := ram(deq_ptr.value)

  if (flow) {
    when(io.queue.enq.valid) { io.queue.deq.valid := true.B }
    when(empty) {
      io.queue.deq.bits := io.queue.enq.bits
      do_deq            := false.B
      when(io.queue.deq.ready) { do_enq := false.B }
    }
  }

  if (pipe) {
    when(io.queue.deq.ready) { io.queue.enq.ready := true.B }
  }

  val ptr_diff = enq_ptr.value - deq_ptr.value

  if (isPow2(entries)) {
    io.queue.count := Mux(maybe_full && ptr_match, entries.U, 0.U) | ptr_diff
  } else {
    io.queue.count := Mux(
      ptr_match,
      Mux(maybe_full, entries.asUInt, 0.U),
      Mux(deq_ptr.value > enq_ptr.value, entries.asUInt + ptr_diff, ptr_diff)
    )
  }
}
