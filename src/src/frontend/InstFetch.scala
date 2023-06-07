package frontend

import axi.AxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import control.bundles.PipelineControlNdPort
import pipeline.dispatch.bundles.InstInfoBundle
import spec.Param.{NaiiveFetchStageState => State}
import spec._
import frontend.bundles.ICacheAccessPort
import frontend.fetch._
import memory.bundles.TlbTransPort
import pipeline.mem.bundles.MemCsrNdPort

class InstFetch extends Module {
  val io = IO(new Bundle {
    val pc       = Input(UInt(Width.Reg.data))
    val pcUpdate = Input(Bool())
    val isNextPc = Output(Bool())

    // <-> Frontend  <->ICache
    val accessPort = Flipped(new ICacheAccessPort)

    // <-> Frontend <-> Instrution queue
    val isFlush         = Input(Bool())
    val instEnqueuePort = Decoupled(new InstInfoBundle)

    // <-> Frontend <-> Tlb
    val tlbTrans = Flipped(new TlbTransPort)
    // <-> Frontend <-> csr
    val csr = Input(new MemCsrNdPort)

  })

  val isFirstSentReg = RegInit(false.B)
  isFirstSentReg := isFirstSentReg

  when(!isFirstSentReg && io.pcUpdate) {
    isFirstSentReg := true.B
  }

  // InstAddr translate and mem stages
  val addrTransStage = Module(new InstAddrTransStage)
  val instReqStage   = Module(new InstReqStage)
  val instResStage   = Module(new InstResStage)

  // addrTransStage
  addrTransStage.io.isFlush       := io.isFlush
  addrTransStage.io.isPcUpdate    := io.pcUpdate
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
  io.instEnqueuePort      <> instResStage.io.out
  instResStage.io.peer.foreach { p =>
    p.memRes <> io.accessPort.res
  }

  // Fallbacks
  io.isNextPc := instReqStage.io.in.ready && isFirstSentReg
}
