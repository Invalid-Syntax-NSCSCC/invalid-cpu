package pipeline.writeback

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Param.isDiffTest
import spec._

class WbStage(changeNum: Int = Param.issueInstInfoMaxNum) extends Module {
  val io = IO(new Bundle {
    // `AddrTransStage` -> `WbStage`
    val gprWriteInfoPort = Input(new RfWriteNdPort)
    // `WbStage` -> `Cu` NO delay
    val gprWritePort = Output(new RfWriteNdPort)

    // Scoreboard
    val freePorts    = Output(Vec(changeNum, new ScoreboardChangeNdPort))
    val csrFreePorts = Output(Vec(changeNum, new ScoreboardChangeNdPort))

    // `AddrTransStage` -> `WbStage` -> `Cu`  NO delay
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

    val difftest =
      if (isDiffTest)
        Some(Output(new Bundle {
          val valid          = Bool()
          val pc             = UInt(Width.Reg.data)
          val instr          = UInt(Width.Reg.data)
          val is_TLBFILL     = Bool() // TODO
          val TLBFILL_index  = UInt(Width.Reg.addr) // TODO
          val is_CNTinst     = Bool() // TODO
          val timer_64_value = UInt(doubleWordLength.W) // TODO
          val wen            = Bool()
          val wdest          = UInt(Width.Reg.addr)
          val wdata          = UInt(Width.Reg.data)
          val csr_rstat      = Bool()
          val csr_data       = UInt(Width.Reg.data)
        }))
      else None
  })

  // Wb debug port connection
  io.instInfoPassThroughPort.out := io.instInfoPassThroughPort.in

  io.gprWritePort := io.gprWriteInfoPort

  // Indicate the availability in scoreboard
  io.freePorts.zip(Seq(io.gprWritePort)).foreach {
    case (freePort, accessInfo) =>
      freePort.en   := accessInfo.en
      freePort.addr := accessInfo.addr
  }

  io.csrFreePorts.zip(Seq(io.instInfoPassThroughPort.in.csrWritePort)).foreach {
    case (freePort, accessInfo) =>
      freePort.en   := accessInfo.en
      freePort.addr := accessInfo.addr
  }

  // Diff test connection
  io.difftest match {
    case Some(dt) =>
      dt           := DontCare
      dt.valid     := io.instInfoPassThroughPort.in.pc.orR
      dt.pc        := io.instInfoPassThroughPort.in.pc
      dt.instr     := io.instInfoPassThroughPort.in.inst
      dt.wen       := RegNext(io.gprWriteInfoPort.en)
      dt.wdest     := RegNext(io.gprWriteInfoPort.addr)
      dt.wdata     := RegNext(io.gprWriteInfoPort.data)
      dt.csr_rstat := RegNext(io.instInfoPassThroughPort.in.csrWritePort.en)
      dt.csr_data  := RegNext(io.instInfoPassThroughPort.in.csrWritePort.data)
    case _ =>
  }
}
