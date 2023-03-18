package axi

import axi.bundles.MasterRead
import axi.types.RegType._
import chisel3._
import spec.Param._

class AxiRegisterRead(val arRegType: RegType, val rRegType: RegType) extends Module {
  val io = IO(new Bundle {
    val slave  = Flipped(new MasterRead)
    val master = new MasterRead
  })

  // AR channel
  arRegType match {
    case BYPASS =>
      io.master.ar <> io.slave.ar
      io.master.ar.bits.user := (if (Axi.Crossbar.aruserEnable) io.slave.ar.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val masterReg       = Reg(chiselTypeOf(io.master.ar.bits))
      val masterValidNext = Wire(Bool())
      val masterValidReg  = RegNext(masterValidNext, false.B)

      val storeInputToOutput = Wire(Bool())

      val slaveReadyEarly = !masterValidNext // enable ready input next cycle if output buffer will be empty
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.ar.ready      := slaveReadyReg
      io.master.ar.bits      := masterReg
      io.master.ar.bits.user := (if (Axi.Crossbar.aruserEnable) masterReg.user else 0.U)
      io.master.ar.valid     := masterValidReg

      // transfer sink ready state to source
      when(slaveReadyReg) {
        masterValidNext    := io.slave.ar.valid
        storeInputToOutput := true.B
      }.elsewhen(io.master.ar.ready) {
        masterValidNext    := false.B
        storeInputToOutput := false.B
      }.otherwise {
        masterValidNext    := masterValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.ar.bits
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val masterReg       = Reg(chiselTypeOf(io.master.ar.bits))
      val masterValidNext = Wire(Bool())
      val masterValidReg  = RegNext(masterValidNext, false.B)

      val tempMasterReg       = Reg(chiselTypeOf(io.master.ar.bits))
      val tempMasterValidNext = Wire(Bool())
      val tempMasterValidReg  = RegNext(tempMasterValidNext, false.B)

      val storeInputToOutput = Wire(Bool())
      val storeInputToTemp   = Wire(Bool())
      val storeTempToOutput  = Wire(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val slaveReadyEarly = io.master.ar.ready | (~tempMasterValidReg & (!masterValidReg | !io.slave.ar.valid))
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.ar.ready      := slaveReadyReg
      io.master.ar.bits      := masterReg
      io.master.ar.bits.user := (if (Axi.Crossbar.aruserEnable) masterReg.user else 0.U)
      io.master.ar.valid     := masterValidReg

      // transfer sink ready state to source
      masterValidNext     := masterValidReg
      tempMasterValidNext := tempMasterValidReg
      storeInputToOutput  := false.B
      storeInputToTemp    := false.B
      storeTempToOutput   := false.B
      when(slaveReadyReg) { // input is ready
        when(io.master.ar.ready | ~masterValidReg) {
          // output is ready or currently not valid, transfer data to output
          masterValidNext    := io.slave.ar.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempMasterValidNext := io.slave.ar.valid
          storeInputToTemp    := true.B
        }
      }.elsewhen(io.master.ar.ready) {
        // input is not ready, but output is ready
        masterValidNext     := tempMasterValidReg
        tempMasterValidNext := false.B
        storeTempToOutput   := true.B
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
      val slaveReg       = Reg(chiselTypeOf(io.slave.r.bits))
      val slaveValidNext = Wire(Bool())
      val slaveValidReg  = RegNext(slaveValidNext, false.B)

      val storeInputToOutput = Reg(Bool())

      val masterReadyEarly = !slaveValidNext // enable ready input next cycle if output buffer will be empty
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.r.ready    := masterReadyReg
      io.slave.r.bits      := slaveReg
      io.slave.r.bits.user := (if (Axi.Crossbar.ruserEnable) slaveReg.user else 0.U)
      io.slave.r.valid     := slaveValidReg

      // transfer sink ready state to source
      when(masterReadyReg) {
        slaveValidNext     := io.master.r.valid
        storeInputToOutput := true.B
      }.elsewhen(io.slave.r.ready) {
        slaveValidNext     := false.B
        storeInputToOutput := false.B
      }.otherwise {
        slaveValidNext     := slaveValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        slaveReg := io.master.r.bits
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val slaveReg       = Reg(chiselTypeOf(io.slave.r.bits))
      val slaveValidNext = Wire(Bool())
      val slaveValidReg  = RegNext(slaveValidNext, false.B)

      val tempSlaveReg       = Reg(chiselTypeOf(io.slave.r.bits))
      val tempSlaveValidNext = Wire(Bool())
      val tempSlaveValidReg  = RegNext(tempSlaveValidNext, false.B)

      val storeInputToOutput = Wire(Bool())
      val storeInputToTemp   = Wire(Bool())
      val storeTempToOutput  = Wire(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val masterReadyEarly = io.slave.r.ready | (~tempSlaveValidReg & (!slaveValidReg | !io.master.r.valid))
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.r.ready    := masterReadyReg
      io.slave.r.bits      := slaveReg
      io.slave.r.bits.user := (if (Axi.Crossbar.ruserEnable) slaveReg.user else 0.U)
      io.slave.r.valid     := slaveValidReg

      // transfer sink ready state to source
      slaveValidNext     := slaveValidReg
      tempSlaveValidNext := tempSlaveValidReg
      storeInputToOutput := false.B
      storeInputToTemp   := false.B
      storeTempToOutput  := false.B
      when(masterReadyReg) { // input is ready
        when(io.slave.r.ready | ~slaveValidReg) {
          slaveValidNext     := io.master.r.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempSlaveValidNext := io.master.r.valid
          storeInputToTemp   := true.B
        }
      }.elsewhen(io.slave.r.ready) {
        slaveValidNext     := tempSlaveValidReg
        tempSlaveValidNext := false.B
        storeTempToOutput  := true.B
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
