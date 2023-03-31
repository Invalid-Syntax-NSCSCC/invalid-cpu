package pipeline.writeback

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfAccessInfoNdPort, RfWriteNdPort}
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Param.isDiffTest
import spec._

class WbStage(changeNum: Int = Param.scoreboardChangeNum) extends Module {
  val io = IO(new Bundle {
    // `MemStage` -> `WbStage`
    val gprWriteInfoPort = Input(new RfWriteNdPort)
    // `WbStage` -> `Cu` NO delay
    val gprWritePort = Output(new RfWriteNdPort)

    // Scoreboard
    val freePorts = Output(Vec(changeNum, new ScoreboardChangeNdPort))

    // `MemStage` -> `WbStage` -> `Cu`  NO delay
    val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

    val difftest =
      if (isDiffTest)
        Some(Output(new Bundle {
          val valid          = Bool() // TODO
          val pc             = UInt(Width.Reg.data)
          val instr          = UInt(Width.Reg.data)
          val is_TLBFILL     = Bool() // TODO
          val TLBFILL_index  = UInt(Width.Reg.addr) // TODO
          val is_CNTinst     = Bool() // TODO
          val timer_64_value = UInt(doubleWordLength.W) // TODO
          val wen            = Bool()
          val wdest          = UInt(Width.Reg.addr)
          val wdata          = UInt(Width.Reg.data)
          val csr_rstat      = Bool() // TODO
          val csr_data       = UInt(Width.Reg.data) // TODO
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

  // Diff test connection
  io.difftest match {
    case Some(dt) =>
      dt       := DontCare
      dt.pc    := RegNext(io.instInfoPassThroughPort.in.pc)
      dt.instr := RegNext(io.instInfoPassThroughPort.in.inst)
      dt.wen   := RegNext(io.gprWriteInfoPort.en)
      dt.wdest := RegNext(io.gprWriteInfoPort.addr)
      dt.wdata := RegNext(io.gprWriteInfoPort.data)
    case _ =>
  }
}
