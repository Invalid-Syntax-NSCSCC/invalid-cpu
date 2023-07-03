package frontend

import chisel3._
import chisel3.util._
import common.Pc
import common.bundles.PcSetNdPort
import frontend.bpu.BPU
import frontend.bundles.{CuCommitFtqPort, ExeFtqPort, ICacheAccessPort}
import memory.bundles.TlbTransPort
import pipeline.dispatch.bundles.FetchInstInfoBundle
import pipeline.memory.bundles.MemCsrNdPort
import pipeline.queue.InstQueueEnqNdPort
import spec._

class Frontend extends Module {
  val io = IO(new Bundle {
    // pc
    val cuNewPc = Input(new PcSetNdPort)
    val isFlush = Input(Bool())

    // ftq <-> exe
    val exeFtqPort = new ExeFtqPort

    // ftq <-> cu
    val cuCommitFtqPort = new CuCommitFtqPort

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
  val ftq       = Module(new FetchTargetQueue())
  val instFetch = Module(new InstFetch)

  pc.io.newPc          := io.cuNewPc
  pc.io.mainRedirectPc := bpu.io.mainRedirectPc
  pc.io.ftqFull        := ftq.io.bpuFtqPort.ftqFull

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
  bpu.io.pc           := pc.io.pc
  bpu.io.bpuFtqPort   <> ftq.io.bpuFtqPort
  bpu.io.backendFlush := io.isFlush

  // fetch Target Pc queue;
  // stage 1
  // act as a fetch buffer
  ftq.io.backendFlush      := io.isFlush
  ftq.io.backendFlushFtqId := 0.U
  ftq.io.instFetchFlush    := false.B // TODO add predecoder stage
  ftq.io.instFetchFtqId    := false.B
  ftq.io.cuCommitFtqPort   <> io.cuCommitFtqPort
  ftq.io.exeFtqPort        <> io.exeFtqPort

  // stage 2-4
  instFetch.io.ftqIFPort       <> ftq.io.ftqIFPort
  instFetch.io.accessPort      <> io.accessPort
  instFetch.io.instDequeuePort <> io.instDequeuePort
  instFetch.io.isFlush         := io.isFlush
  instFetch.io.csr             := io.csr
  instFetch.io.tlbTrans        <> io.tlbTrans

}
