package frontend

import chisel3._
import chisel3.util._
import frontend.bundles.ICacheAccessPort
import frontend.fetch._
import memory.bundles.TlbTransPort
import pipeline.dispatch.bundles.InstInfoBundle
import pipeline.memory.bundles.MemCsrNdPort
import spec._

class InstFetch extends Module {
  val io = IO(new Bundle {
    val pc       = Input(UInt(Width.Reg.data))
    val pcUpdate = Input(Bool())
    val isPcNext = Output(Bool())

    // <-> Frontend  <->ICache
    val accessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val isFlush         = Input(Bool())
    val instDequeuePort = Decoupled(new InstInfoBundle)

    // <-> Frontend <-> Tlb
    val tlbTrans = Flipped(new TlbTransPort)
    // <-> Frontend <-> csr
    val csr = Input(new MemCsrNdPort)

  })

  val isFirstSentReg = RegInit(false.B)
  isFirstSentReg := true.B

  // InstAddr translate and mem stages
  val addrTransStage = Module(new InstAddrTransStage)
  val instReqStage   = Module(new InstReqStage)
  val instResStage   = Module(new InstResStage)

  // addrTransStage
  addrTransStage.io.isFlush       := io.isFlush
  addrTransStage.io.isPcUpdate    := io.pcUpdate || !isFirstSentReg
  addrTransStage.io.pc            := io.pc
  addrTransStage.io.peer.csr      := io.csr
  addrTransStage.io.peer.tlbTrans <> io.tlbTrans

  // instReqStage
  instReqStage.io.isFlush := io.isFlush
  instReqStage.io.in      <> addrTransStage.io.out
  instReqStage.io.peer.foreach { p =>
    p.memReq <> io.accessPort.req
  }

  // instResStage
  instResStage.io.isFlush := io.isFlush
  instResStage.io.in      <> instReqStage.io.out
  io.instDequeuePort      <> instResStage.io.out
  instResStage.io.peer.foreach { p =>
    p.memRes <> io.accessPort.res
  }

  io.isPcNext := instReqStage.io.in.ready && isFirstSentReg
}
