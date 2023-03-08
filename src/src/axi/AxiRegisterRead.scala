package axi

import axi.bundles.MasterRegisterRead
import axi.types.RegType._
import chisel3._
import spec._

class AxiRegisterRead(val idWidth: Int, val arRegType: RegType, val rRegType: RegType) extends Module {
  val io = IO(new Bundle {
    val slave  = Flipped(new MasterRegisterRead(idWidth))
    val master = new MasterRegisterRead(idWidth)
  })

  // AR channel
  arRegType match {
    case BYPASS =>
      io.master.ar <> io.slave.ar
      io.master.ar.bits.user := (if (Axi.Crossbar.aruserEnable) io.slave.ar.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val masterReg          = Reg(chiselTypeOf(io.master.ar.bits))
      val masterValidNextReg = Reg(Bool())
      val masterValidReg     = RegNext(masterValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())

      val slaveReadyEarly = !masterValidNextReg // enable ready input next cycle if output buffer will be empty
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.ar.ready      := slaveReadyReg
      io.master.ar.bits      := masterReg
      io.master.ar.bits.user := (if (Axi.Crossbar.aruserEnable) masterReg.user else 0.U)
      io.master.ar.valid     := masterValidReg

      // transfer sink ready state to source
      when(slaveReadyReg) {
        masterValidNextReg := io.slave.ar.valid
        storeInputToOutput := true.B
      }.elsewhen(io.master.ar.ready) {
        masterValidNextReg := false.B
        storeInputToOutput := false.B
      }.otherwise {
        masterValidNextReg := masterValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.ar.bits
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val masterReg          = Reg(chiselTypeOf(io.master.ar.bits))
      val masterValidNextReg = Reg(Bool())
      val masterValidReg     = RegNext(masterValidNextReg, false.B)

      val tempMasterReg          = Reg(chiselTypeOf(io.master.ar.bits))
      val tempMasterValidNextReg = Reg(Bool())
      val tempMasterValidReg     = RegNext(tempMasterValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())
      val storeInputToTemp   = Reg(Bool())
      val storeTempToOutput  = Reg(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val slaveReadyEarly = io.master.ar.ready | (~tempMasterValidReg & (!masterValidReg | !io.slave.ar.valid))
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.ar.ready      := slaveReadyReg
      io.master.ar.bits      := masterReg
      io.master.ar.bits.user := (if (Axi.Crossbar.aruserEnable) masterReg.user else 0.U)
      io.master.ar.valid     := masterValidReg

      // transfer sink ready state to source
      masterValidNextReg     := masterValidReg
      tempMasterValidNextReg := tempMasterValidReg
      storeInputToOutput     := false.B
      storeInputToTemp       := false.B
      storeTempToOutput      := false.B
      when(slaveReadyReg) { // input is ready
        when(io.master.ar.ready | ~masterValidReg) {
          // output is ready or currently not valid, transfer data to output
          masterValidNextReg := io.slave.ar.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempMasterValidNextReg := io.slave.ar.valid
          storeInputToTemp       := true.B
        }
      }.elsewhen(io.master.ar.ready) {
        // input is not ready, but output is ready
        masterValidNextReg     := tempMasterValidReg
        tempMasterValidNextReg := false.B
        storeTempToOutput      := true.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.ar.bits
      }.elsewhen(storeTempToOutput) {
        masterReg := tempMasterReg
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }

      when(storeInputToTemp) {
        tempMasterReg := io.slave.ar.bits
      }.otherwise {
        tempMasterReg := 0.U.asTypeOf(masterReg)
      }
  }

  // R channel
  rRegType match {
    case BYPASS =>
      io.slave.r <> io.master.r
      io.slave.r.bits.user := (if (Axi.Crossbar.ruserEnable) io.master.r.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val slaveReg          = Reg(chiselTypeOf(io.slave.r.bits))
      val slaveValidNextReg = Reg(Bool())
      val slaveValidReg     = RegNext(slaveValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())

      val masterReadyEarly = !slaveValidNextReg // enable ready input next cycle if output buffer will be empty
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.r.ready    := masterReadyReg
      io.slave.r.bits      := slaveReg
      io.slave.r.bits.user := (if (Axi.Crossbar.ruserEnable) slaveReg.user else 0.U)
      io.slave.r.valid     := slaveValidReg

      // transfer sink ready state to source
      when(masterReadyReg) {
        slaveValidNextReg  := io.master.r.valid
        storeInputToOutput := true.B
      }.elsewhen(io.slave.r.ready) {
        slaveValidNextReg  := false.B
        storeInputToOutput := false.B
      }.otherwise {
        slaveValidNextReg  := slaveValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        slaveReg := io.master.r.bits
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val slaveReg          = Reg(chiselTypeOf(io.slave.r.bits))
      val slaveValidNextReg = Reg(Bool())
      val slaveValidReg     = RegNext(slaveValidNextReg, false.B)

      val tempSlaveReg          = Reg(chiselTypeOf(io.slave.r.bits))
      val tempSlaveValidNextReg = Reg(Bool())
      val tempSlaveValidReg     = RegNext(tempSlaveValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())
      val storeInputToTemp   = Reg(Bool())
      val storeTempToOutput  = Reg(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val masterReadyEarly = io.slave.r.ready | (~tempSlaveValidReg & (!slaveValidReg | !io.master.r.valid))
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.r.ready    := masterReadyReg
      io.slave.r.bits      := slaveReg
      io.slave.r.bits.user := (if (Axi.Crossbar.ruserEnable) slaveReg.user else 0.U)
      io.slave.r.valid     := slaveValidReg

      // transfer sink ready state to source
      slaveValidNextReg     := slaveValidReg
      tempSlaveValidNextReg := tempSlaveValidReg
      storeInputToOutput    := false.B
      storeInputToTemp      := false.B
      storeTempToOutput     := false.B
      when(masterReadyReg) { // input is ready
        when(io.slave.r.ready | ~slaveValidReg) {
          slaveValidNextReg  := io.master.r.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempSlaveValidNextReg := io.master.r.valid
          storeInputToTemp      := true.B
        }
      }.elsewhen(io.slave.r.ready) {
        slaveValidNextReg     := tempSlaveValidReg
        tempSlaveValidNextReg := false.B
        storeTempToOutput     := true.B
      }

      when(storeInputToOutput) {
        slaveReg := io.master.r.bits
      }.elsewhen(storeTempToOutput) {
        slaveReg := tempSlaveReg
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }

      when(storeInputToTemp) {
        slaveReg := io.master.r.bits
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }
  }
}
