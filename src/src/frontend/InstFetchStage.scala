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
import memory.bundles.TlbTransPort
import pipeline.mem.bundles.MemCsrNdPort

class InstFetchStage extends Module {
  val io = IO(new Bundle {
    val pc       = Input(UInt(Width.Reg.data))
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
  addrTransStage.io.in.valid                   := true.B // todo change to fetchTargetQueue.entryBlock.valid
  addrTransStage.io.out.ready                  := true.B
  addrTransStage.io.in.bits.memRequest.isValid := (io.pc =/= 0.U(Width.Reg.data))
  addrTransStage.io.in.bits.memRequest.addr    := io.pc
  addrTransStage.io.isFlush                    := io.isFlush
  addrTransStage.io.peer.foreach { p =>
    p.tlbTrans <> io.tlbTrans
    p.csr      := io.csr
  }
  val pcBeforeTrans = RegNext(io.pc, spec.zeroWord)
  val pcTrans       = WireDefault(io.pc)
  pcTrans := Mux(
    addrTransStage.io.out.bits.translatedMemReq.isValid,
    addrTransStage.io.out.bits.translatedMemReq.addr,
    pcBeforeTrans
  )

  io.instEnqueuePort.bits.pcAddr := pcBeforeTrans
  io.instEnqueuePort.bits.inst   := io.accessPort.res.read.data

  val stateReg = RegInit(State.idle)
  stateReg := stateReg

  val shouldDiscardReg = RegInit(false.B) // Fallback: Follow
  val shouldDiscard    = WireInit(io.isFlush || shouldDiscardReg)

  val isCompleteReg = RegInit(false.B)
  isCompleteReg := isCompleteReg
  val lastInstReg = RegInit(0.U(Width.inst))
  lastInstReg := lastInstReg

  // Fallbacks
  io.isPcNext                      := false.B
  io.accessPort.req.client.isValid := false.B
  io.accessPort.req.client.addr    := pcTrans
  io.instEnqueuePort.valid         := false.B

  switch(stateReg) {
    is(State.idle) { // State Value: 0
      stateReg := State.request
    }
    is(State.request) { // State Value: 1
      when(io.accessPort.req.isReady && addrTransStage.io.out.valid) {
        when(addrTransStage.io.out.bits.translatedMemReq.isValid) {
          stateReg                         := State.waitQueue
          io.accessPort.req.client.isValid := true.B
          isCompleteReg                    := false.B
        }.otherwise {
          stateReg := State.request
        }

      }
    }

    is(State.waitQueue) { // State Value: 2
      shouldDiscardReg := shouldDiscard
      when(io.accessPort.res.isComplete) {
        isCompleteReg := true.B
        lastInstReg   := io.accessPort.res.read.data
      }
      when(isCompleteReg) {
        io.instEnqueuePort.bits.inst := lastInstReg
      }
      when(io.accessPort.res.isComplete || isCompleteReg) {
        io.instEnqueuePort.valid := true.B
        when(shouldDiscard) {
          io.instEnqueuePort.valid := false.B
          stateReg                 := State.request
          shouldDiscardReg         := false.B
        }.elsewhen(io.instEnqueuePort.ready) {
          stateReg         := State.request
          shouldDiscardReg := false.B
          io.isPcNext      := true.B

          when(io.accessPort.req.isReady) {
            stateReg                         := State.waitQueue
            io.accessPort.req.client.addr    := Mux(shouldDiscard, pcTrans, pcTrans + 4.U)
            io.accessPort.req.client.isValid := true.B
            isCompleteReg                    := false.B

          }.otherwise {
            stateReg := State.request
          }
        }
      }
    }
  }
}
