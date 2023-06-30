package frontend

import chisel3._
import chisel3.util._
import frontend.bundles.ICacheAccessPort
import memory.bundles.TlbTransPort
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.memory.bundles.MemCsrNdPort
import pipeline.queue.InstQueueEnqNdPort
import spec._

class Frontend extends Module {
  val io = IO(new Bundle {
    // <-> ICache
    val accessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val pc       = Input(UInt(Width.Reg.data))
    val pcUpdate = Input(Bool())
    val isPcNext = Output(Bool())

    val isFlush         = Input(Bool())
    val instDequeuePort = Decoupled(new InstQueueEnqNdPort)

    // TODO mul FetchNum
    //     // val instEnqueuePorts = Vec(Param.Count.frontend.instFetchNum, Flipped(Decoupled(new InstInfoBundle)))

    // instFetch <-> Tlb
    val tlbTrans = Flipped(new TlbTransPort)
    //  InstFetch <-> csr
    val csr = Input(new MemCsrNdPort)
  })

  val instFetch = Module(new InstFetch)
  instFetch.io.accessPort      <> io.accessPort
  instFetch.io.instDequeuePort <> io.instDequeuePort
  instFetch.io.isFlush         := io.isFlush
  instFetch.io.pc              := io.pc
  instFetch.io.pcUpdate        := io.pcUpdate
  io.isPcNext                  := instFetch.io.isPcNext
  instFetch.io.csr             := io.csr
  instFetch.io.tlbTrans        <> io.tlbTrans
}
