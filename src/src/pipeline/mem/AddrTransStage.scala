package pipeline.mem

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import memory.bundles.MemRequestNdPort
import pipeline.mem.bundles.MemCsrNdPort
import pipeline.mem.enums.AddrTransMode
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Value.Csr
import spec.Width

class AddrTransStage extends Module {
  val io = IO(new Bundle {
    val memAccessPort = Input(new MemRequestNdPort)
    val csrPort       = Input(new MemCsrNdPort)

    // (Next clock pulse)
    val gprWritePassThroughPort  = new PassThroughPort(new RfWriteNdPort)
    val instInfoPassThroughPort  = new PassThroughPort(new InstInfoNdPort)
    val translatedMemRequestPort = Output(new MemRequestNdPort)
    val isCachedAccess           = Output(Bool())
  })

  // Pass GPR write request to the next stage
  val gprWriteReg = RegNext(io.gprWritePassThroughPort.in)
  io.gprWritePassThroughPort.out := gprWriteReg

  // Wb debug port connection
  val instInfoReg = RegNext(io.instInfoPassThroughPort.in)
  io.instInfoPassThroughPort.out := instInfoReg

  // 强烈谴责龙芯依托答辩般的文档

  // TODO: Might need to move to previous stage to prevent data hazard
  // Select a translation mode
  val transMode = WireDefault(AddrTransMode.direct) // Fallback: Direct translation
  when(!io.csrPort.crmd.da && io.csrPort.crmd.pg) {
    val isDirectMappingWindowHit = WireDefault(
      io.csrPort.dmw.vseg ===
        io.memAccessPort.addr(io.memAccessPort.addr.getWidth - 1, io.memAccessPort.addr.getWidth - 3)
    )

    switch(io.csrPort.crmd.plv) {
      is(Csr.Crmd.Plv.low) {
        when(
          io.csrPort.dmw.plv3 && isDirectMappingWindowHit
        ) {
          transMode := AddrTransMode.directMapping
        }.otherwise {
          transMode := AddrTransMode.pageTableMapping
        }
      }
      is(Csr.Crmd.Plv.high) {
        when(io.csrPort.dmw.plv0 && isDirectMappingWindowHit) {
          transMode := AddrTransMode.directMapping
        }.otherwise {
          transMode := AddrTransMode.pageTableMapping
        }
      }
    }
  }

  // Translate address
  val physicalAddr = WireDefault(0.U(Width.Mem.addr)) // Fallback: Dummy address
  switch(transMode) {
    is(AddrTransMode.direct) {
      physicalAddr := io.memAccessPort.addr
    }
    is(AddrTransMode.directMapping) {
      physicalAddr := Cat(
        io.csrPort.dmw.pseg,
        io.memAccessPort.addr(io.memAccessPort.addr.getWidth - 4, 0)
      )
    }
    is(AddrTransMode.pageTableMapping) {
      // TODO: Finish page table mapping (alongside with TLB)
      physicalAddr := 0.U
    }
  }

  // TODO: Might need to move to previous stage to prevent data hazard
  // Can use cache
  val isCachedAccessReg = RegNext(true.B, true.B) // Fallback: Coherent cached
  io.isCachedAccess := isCachedAccessReg
  switch(io.csrPort.crmd.datm) {
    is(Csr.Crmd.Datm.suc) {
      isCachedAccessReg := false.B
    }
    is(Csr.Crmd.Datm.cc) {
      isCachedAccessReg := true.B
    }
  }

  // Pass translated memory request to the next stage
  val translatedMemRequestReg = RegNext(io.memAccessPort)
  translatedMemRequestReg.addr := physicalAddr
  io.translatedMemRequestPort  := translatedMemRequestReg
}
