package frontend

import chisel3._
import chisel3.util._
import frontend.bundles.{FtqIFPort, ICacheAccessPort}
import frontend.fetch._
import memory.bundles.TlbTransPort
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.memory.bundles.MemCsrNdPort
import pipeline.queue.InstQueueEnqNdPort
import spec._

class InstFetch extends Module {
  val io = IO(new Bundle {
    // <-> Frontend <-> FetchTargetQueue
    val ftqIFPort = new FtqIFPort

    // <-> Frontend  <->ICache
    val accessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val isFlush         = Input(Bool())
    val instDequeuePort = Decoupled(new InstQueueEnqNdPort)

    // <-> Frontend <-> Tlb
    val tlbTrans = Flipped(new TlbTransPort)
    // <-> Frontend <-> csr
    val csr = Input(new MemCsrNdPort)
  })

  // InstAddr translate and mem stages
  val addrTransStage = Module(new InstAddrTransStage)
  val instReqStage   = Module(new InstReqStage)
  val instResStage   = Module(new InstResStage)

  // addrTransStage
  addrTransStage.io.isFlush       := io.isFlush || (io.ftqIFPort.redirect && io.ftqIFPort.ready)
  addrTransStage.io.ftqBlock      := io.ftqIFPort.ftqBlockBundle
  addrTransStage.io.ftqId         := io.ftqIFPort.ftqId
  addrTransStage.io.peer.csr      := io.csr
  addrTransStage.io.peer.tlbTrans <> io.tlbTrans

  // instReqStage
  instReqStage.io.isFlush := io.isFlush
  instReqStage.io.in      <> addrTransStage.io.out
  instReqStage.io.peer.foreach { p =>
    p.memReq      <> io.accessPort.req
    p.ftqRedirect := io.ftqIFPort.redirect
  }

  // instResStage
  instResStage.io.isFlush := io.isFlush
  instResStage.io.in      <> instReqStage.io.out
  io.instDequeuePort      <> instResStage.io.out
  instResStage.io.peer.foreach { p =>
    p.memRes <> io.accessPort.res
  }

  io.ftqIFPort.ready := instReqStage.io.in.ready && !addrTransStage.io.isBlockPcNext
}
