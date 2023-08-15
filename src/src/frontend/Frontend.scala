package frontend

import chisel3._
import chisel3.util._
import common.Pc
import common.bundles.BackendRedirectPcNdPort
import frontend.bpu.BPU
import frontend.bundles.{CommitFtqTrainNdPort, ExeFtqPort, ICacheAccessPort}
import memory.bundles.TlbTransPort
import pipeline.common.bundles.{InstQueueEnqNdPort, MemCsrNdPort}
import spec._

class Frontend extends Module {
  val io = IO(new Bundle {
    // pc
    val cuNewPc       = Input(new BackendRedirectPcNdPort)
    val isFlush       = Input(Bool()) // exe flush || cu flush
    val isFlushFromCu = Input(Bool()) // only indicate signal from cu;should cooperate with isFlush
    val ftqFlushId    = Input(UInt(Param.BPU.ftqPtrWidth.W))

    // ftq <-> exe
    val exeFtqPort = new ExeFtqPort

    // ftq <-> cu
    val commitFtqTrainPort = Input(new CommitFtqTrainNdPort)
    val commitBitMask      = Input(Vec(Param.commitNum, Bool()))
    val commitFixBranch    = Input(Bool())
    val commitFixId        = Input(UInt(Param.BPU.ftqPtrWidth.W))

    // instFetch <-> ICache
    val accessPort = Flipped(new ICacheAccessPort)

    // instFetch <-> Instrution queues
    val instDequeuePort = Decoupled(new InstQueueEnqNdPort)

    // instFetch <-> Tlb
    val tlbTrans = Flipped(new TlbTransPort)
    //  InstFetch <-> csr
    val csr = Input(new MemCsrNdPort)
  })
  val pc        = Module(new Pc)
  val bpu       = Module(new BPU)
  val ftq       = Module(new FetchTargetQueue)
  val instFetch = Module(new InstFetch)

  pc.io.newPc             := io.cuNewPc
  pc.io.bpuRedirectPc     := bpu.io.bpuRedirectPc
  pc.io.ftqFull           := ftq.io.bpuFtqPort.ftqFull
  pc.io.fetchNum          := bpu.io.fetchNum
  pc.io.preDecodePc.bits  := instFetch.io.preDecodeRedirectPort.redirectPc
  pc.io.preDecodePc.valid := instFetch.io.preDecodeRedirectPort.predecodeRedirect

  // Frontend pipeline structure
  // bpu(branch predict unit) => ftq(fetchTargetQueue) =>
  // instFetch( addressTranslateStage => instMemRequestStage => instMemResultStage) =>
  // instQueues  (act as middle buffer)
  // => backend

  // branch predict unit
  // stage 0 send fallback target pc ;
  // stage 1 when hit(fetch target buffer) =>
  //         access bram and send predict target pc in next stage ,
  //         quit the fallback target and use the new predict pc(modify ftq);
  //          if the pc has send to instFetch, then flush it  (by mainRedirect signal )
  bpu.io.pc             := pc.io.pc
  bpu.io.bpuFtqPort     <> ftq.io.bpuFtqPort
  bpu.io.backendFlush   := io.isFlush
  bpu.io.preDecodeFlush := instFetch.io.preDecodeRedirectPort.predecodeRedirect
  bpu.io.isFlushFromCu  := io.isFlushFromCu

  // fetch Target Pc queue;
  // stage 1
  // act as a fetch buffer
  ftq.io.backendFlush          := io.isFlush
  ftq.io.backendFlushFtqId     := io.ftqFlushId
  ftq.io.preDecoderFlush       := instFetch.io.preDecodeRedirectPort.predecodeRedirect // TODO add predecoder stage
  ftq.io.preDecoderFtqId       := instFetch.io.preDecodeRedirectPort.redirectFtqId
  ftq.io.preDecoderBranchTaken := instFetch.io.preDecodeRedirectPort.predecoderBranch
  instFetch.io.preDecodeRedirectPort.commitRasPort := ftq.io.ftqRasPort
  ftq.io.commitFtqTrainPort                        := io.commitFtqTrainPort
  ftq.io.exeFtqPort                                <> io.exeFtqPort
  ftq.io.commitBitMask.zip(io.commitBitMask).foreach {
    case (dst, src) =>
      dst := src
  }
  if (Param.isNoPrivilege && Param.exeFeedBackFtqDelay) {
    ftq.io.exeFtqPort.feedBack.commitBundle.ftqMetaUpdateJumpTarget := io.cuNewPc.pcAddr
  }

  // stage 2-4
  instFetch.io.ftqIFPort       <> ftq.io.ftqIFPort
  instFetch.io.accessPort      <> io.accessPort
  instFetch.io.instDequeuePort <> io.instDequeuePort
  instFetch.io.isFlush         := io.isFlush
  instFetch.io.csr             := io.csr
  instFetch.io.tlbTrans        <> io.tlbTrans
}
