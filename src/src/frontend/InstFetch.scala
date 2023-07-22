package frontend

import chisel3._
import chisel3.util._
import frontend.bundles.{FtqIFNdPort, ICacheAccessPort}
import frontend.fetch._
import memory.bundles.TlbTransPort
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.memory.bundles.MemCsrNdPort
import pipeline.queue.InstQueueEnqNdPort
import spec._

class InstFetch extends Module {
  val io = IO(new Bundle {
    // <-> Frontend <-> FetchTargetQueue
    val ftqIFPort = Flipped(Decoupled(new FtqIFNdPort))

    // <-> Frontend  <->ICache
    val accessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val isFlush         = Input(Bool())
    val instDequeuePort = Decoupled(new InstQueueEnqNdPort)

    // <-> Frontend <-> Tlb
    val tlbTrans = Flipped(new TlbTransPort)
    // <-> Frontend <-> csr
    val csr = Input(new MemCsrNdPort)

    // <-> Frontend <-> Pc,Ftq,
    val preDecodeRedirectPort = Output(new InstPreDecodePeerPort)
  })

  // InstAddr translate and mem stages
  val addrTransStage     = Module(new InstAddrTransStage)
  val instReqStage       = Module(new InstReqStage)
  val instResStage       = Module(new InstResStage)
  val instPreDecodeStage = Module(new InstPreDecodeStage)

  // addrTransStage
  addrTransStage.io.isFlush := io.isFlush || instPreDecodeStage.io.peer.get.predecodeRedirect
  addrTransStage.io.in      <> io.ftqIFPort
  addrTransStage.io.peer.foreach { p =>
    p.csr      <> io.csr
    p.tlbTrans <> io.tlbTrans
  }

  // instReqStage
  instReqStage.io.isFlush := io.isFlush || instPreDecodeStage.io.peer.get.predecodeRedirect
  instReqStage.io.in      <> addrTransStage.io.out
  instReqStage.io.peer.foreach { p =>
    p.memReq <> io.accessPort.req
  }

  // instResStage
  instResStage.io.isFlush := io.isFlush || instPreDecodeStage.io.peer.get.predecodeRedirect
  instResStage.io.in      <> instReqStage.io.out
  io.instDequeuePort      <> instResStage.io.out
  instResStage.io.peer.foreach { p =>
    p.memRes <> io.accessPort.res
  }

  // instPreDecodeStage
  instPreDecodeStage.io.isFlush := io.isFlush
  instPreDecodeStage.io.in      <> instResStage.out
  io.instDequeuePort            <> instPreDecodeStage.io.out
  instPreDecodeStage.io.peer.foreach { p =>
    p <> io.preDecodeRedirectPort
  }

}
