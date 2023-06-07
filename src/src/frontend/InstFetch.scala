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
import frontend.instFetchStages._
import memory.bundles.TlbTransPort
import pipeline.mem.bundles.MemCsrNdPort

class InstFetch extends Module {
  val io = IO(new Bundle {
    val pc       = Input(UInt(Width.Reg.data))
    val pcUpdate = Input(Bool())
    val isPcNext = Output(Bool())

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

  // InstAddr translate and mem stages
  val addrTransStage = Module(new InstAddrTransStage)
  val instReqStage   = Module(new InstReqStage)
  val instResStage   = Module(new InstResStage)

  // addrTransStage
  addrTransStage.io.in.valid                   := io.pcUpdate
  addrTransStage.io.in.bits.memRequest.isValid := (io.pc =/= 0.U(Width.Reg.data)) || (!(io.pc(1, 0).xorR))
  addrTransStage.io.in.bits.memRequest.addr    := io.pc
  addrTransStage.io.isFlush                    := io.isFlush
  addrTransStage.io.out                        <> instReqStage.io.in
  addrTransStage.io.peer.foreach { p =>
    p.tlbTrans <> io.tlbTrans
    p.csr      := io.csr
  }

  // instReqStage
  instReqStage.io.in      <> addrTransStage.io.out
  instReqStage.io.isFlush := io.isFlush
  instReqStage.io.peer.foreach { p =>
    p.iCacheReq          <> io.accessPort.req
  }

  // instResStage
  instResStage.io.in      <> instReqStage.io.out
  instResStage.io.isFlush := io.isFlush
  io.instEnqueuePort      <> instResStage.io.out
  instResStage.io.peer.foreach { p =>
    p.res <> io.accessPort.res
  }

  // Fallbacks
  io.isPcNext              := false.B
  io.instEnqueuePort.valid := false.B
  when(addrTransStage.io.in.ready) {
    io.isPcNext := true.B
  }
}
