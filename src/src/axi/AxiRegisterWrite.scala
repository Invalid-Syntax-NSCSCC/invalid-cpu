package axi

import axi.bundles.MasterWrite
import axi.types.RegType._
import chisel3._
import spec._

class AxiRegisterWrite(val awRegType: RegType, val wRegType: RegType, val bRegType: RegType) extends Module {
  val io = IO(new Bundle {
    val slave  = Flipped(new MasterWrite)
    val master = new MasterWrite
  })

  // AW channel
  awRegType match {
    case BYPASS =>
      io.master.aw <> io.slave.aw
      io.master.aw.bits.user := (if (Param.Axi.Crossbar.awuserEnable) io.slave.aw.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val masterReg       = Reg(chiselTypeOf(io.master.aw.bits))
      val masterValidNext = Wire(Bool())
      val masterValidReg  = RegNext(masterValidNext, false.B)

      val storeInputToOutput = Wire(Bool())

      val slaveReadyEarly = !masterValidNext // enable ready input next cycle if output buffer will be empty
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.aw.ready      := slaveReadyReg
      io.master.aw.bits      := masterReg
      io.master.aw.bits.user := (if (Param.Axi.Crossbar.awuserEnable) masterReg.user else 0.U)
      io.master.aw.valid     := masterValidReg

      // transfer sink ready state to source
      when(slaveReadyReg) {
        masterValidNext    := io.slave.aw.valid
        storeInputToOutput := true.B
      }.elsewhen(io.master.aw.ready) {
        masterValidNext    := false.B
        storeInputToOutput := false.B
      }.otherwise {
        masterValidNext    := masterValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.aw.bits
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val masterReg       = Reg(chiselTypeOf(io.master.aw.bits))
      val masterValidNext = Wire(Bool())
      val masterValidReg  = RegNext(masterValidNext, false.B)

      val tempMasterReg       = Reg(chiselTypeOf(io.master.aw.bits))
      val tempMasterValidNext = Wire(Bool())
      val tempMasterValidReg  = RegNext(tempMasterValidNext, false.B)

      val storeInputToOutput = Wire(Bool())
      val storeInputToTemp   = Wire(Bool())
      val storeTempToOutput  = Wire(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val slaveReadyEarly = io.master.aw.ready | (~tempMasterValidReg & (!masterValidReg | !io.slave.aw.valid))
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.aw.ready      := slaveReadyReg
      io.master.aw.bits      := masterReg
      io.master.aw.bits.user := (if (Param.Axi.Crossbar.awuserEnable) masterReg.user else 0.U)
      io.master.aw.valid     := masterValidReg

      // transfer sink ready state to source
      masterValidNext     := masterValidReg
      tempMasterValidNext := tempMasterValidReg
      storeInputToOutput  := false.B
      storeInputToTemp    := false.B
      storeTempToOutput   := false.B
      when(slaveReadyReg) { // input is ready
        when(io.master.aw.ready | ~masterValidReg) {
          // output is ready or currently not valid, transfer data to output
          masterValidNext    := io.slave.aw.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempMasterValidNext := io.slave.aw.valid
          storeInputToTemp    := true.B
        }
      }.elsewhen(io.master.aw.ready) {
        // input is not ready, but output is ready
        masterValidNext     := tempMasterValidReg
        tempMasterValidNext := false.B
        storeTempToOutput   := true.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.aw.bits
      }.elsewhen(storeTempToOutput) {
        masterReg := tempMasterReg
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }

      when(storeInputToTemp) {
        tempMasterReg := io.slave.aw.bits
      }.otherwise {
        tempMasterReg := 0.U.asTypeOf(masterReg)
      }
  }

  // W channel
  wRegType match {
    case BYPASS =>
      io.master.w <> io.slave.w
      io.master.w.bits.user := (if (Param.Axi.Crossbar.wuserEnable) io.slave.w.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val masterReg       = Reg(chiselTypeOf(io.master.w.bits))
      val masterValidNext = Wire(Bool())
      val masterValidReg  = RegNext(masterValidNext, false.B)

      val storeInputToOutput = Wire(Bool())

      val slaveReadyEarly = !masterValidNext // enable ready input next cycle if output buffer will be empty
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.w.ready      := slaveReadyReg
      io.master.w.bits      := masterReg
      io.master.w.bits.user := (if (Param.Axi.Crossbar.wuserEnable) masterReg.user else 0.U)
      io.master.w.valid     := masterValidReg

      // transfer sink ready state to source
      when(slaveReadyReg) {
        masterValidNext    := io.slave.w.valid
        storeInputToOutput := true.B
      }.elsewhen(io.master.w.ready) {
        masterValidNext    := false.B
        storeInputToOutput := false.B
      }.otherwise {
        masterValidNext    := masterValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.w.bits
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val masterReg       = Reg(chiselTypeOf(io.master.w.bits))
      val masterValidNext = Wire(Bool())
      val masterValidReg  = RegNext(masterValidNext, false.B)

      val tempMasterReg       = Reg(chiselTypeOf(io.master.w.bits))
      val tempMasterValidNext = Wire(Bool())
      val tempMasterValidReg  = RegNext(tempMasterValidNext, false.B)

      val storeInputToOutput = Wire(Bool())
      val storeInputToTemp   = Wire(Bool())
      val storeTempToOutput  = Wire(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val slaveReadyEarly = io.master.w.ready | (~tempMasterValidReg & (!masterValidReg | !io.slave.w.valid))
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.w.ready      := slaveReadyReg
      io.master.w.bits      := masterReg
      io.master.w.bits.user := (if (Param.Axi.Crossbar.wuserEnable) masterReg.user else 0.U)
      io.master.w.valid     := masterValidReg

      // transfer sink ready state to source
      masterValidNext     := masterValidReg
      tempMasterValidNext := tempMasterValidReg
      storeInputToOutput  := false.B
      storeInputToTemp    := false.B
      storeTempToOutput   := false.B
      when(slaveReadyReg) { // input is ready
        when(io.master.w.ready | ~masterValidReg) {
          // output is ready or currently not valid, transfer data to output
          masterValidNext    := io.slave.w.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempMasterValidNext := io.slave.w.valid
          storeInputToTemp    := true.B
        }
      }.elsewhen(io.master.w.ready) {
        // input is not ready, but output is ready
        masterValidNext     := tempMasterValidReg
        tempMasterValidNext := false.B
        storeTempToOutput   := true.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.w.bits
      }.elsewhen(storeTempToOutput) {
        masterReg := tempMasterReg
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }

      when(storeInputToTemp) {
        tempMasterReg := io.slave.w.bits
      }.otherwise {
        tempMasterReg := 0.U.asTypeOf(masterReg)
      }
  }

  // B channel
  bRegType match {
    case BYPASS =>
      io.slave.b <> io.master.b
      io.slave.b.bits.user := (if (Param.Axi.Crossbar.buserEnable) io.master.b.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val slaveReg       = Reg(chiselTypeOf(io.slave.b.bits))
      val slaveValidNext = Wire(Bool())
      val slaveValidReg  = RegNext(slaveValidNext, false.B)

      val storeInputToOutput = Wire(Bool())

      val masterReadyEarly = !slaveValidNext // enable ready input next cycle if output buffer will be empty
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.b.ready    := masterReadyReg
      io.slave.b.bits      := slaveReg
      io.slave.b.bits.user := (if (Param.Axi.Crossbar.buserEnable) slaveReg.user else 0.U)
      io.slave.b.valid     := slaveValidReg

      // transfer sink ready state to source
      when(masterReadyReg) {
        slaveValidNext     := io.master.b.valid
        storeInputToOutput := true.B
      }.elsewhen(io.slave.b.ready) {
        slaveValidNext     := false.B
        storeInputToOutput := false.B
      }.otherwise {
        slaveValidNext     := slaveValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        slaveReg := io.master.b.bits
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val slaveReg       = Reg(chiselTypeOf(io.slave.b.bits))
      val slaveValidNext = Wire(Bool())
      val slaveValidReg  = RegNext(slaveValidNext, false.B)

      val tempSlaveReg       = Reg(chiselTypeOf(io.slave.b.bits))
      val tempSlaveValidNext = Wire(Bool())
      val tempSlaveValidReg  = RegNext(tempSlaveValidNext, false.B)

      val storeInputToOutput = Wire(Bool())
      val storeInputToTemp   = Wire(Bool())
      val storeTempToOutput  = Wire(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val masterReadyEarly = io.slave.b.ready | (~tempSlaveValidReg & (!slaveValidReg | !io.master.b.valid))
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.b.ready    := masterReadyReg
      io.slave.b.bits      := slaveReg
      io.slave.b.bits.user := (if (Param.Axi.Crossbar.buserEnable) slaveReg.user else 0.U)
      io.slave.b.valid     := slaveValidReg

      // transfer sink ready state to source
      slaveValidNext     := slaveValidReg
      tempSlaveValidNext := tempSlaveValidReg
      storeInputToOutput := false.B
      storeInputToTemp   := false.B
      storeTempToOutput  := false.B
      when(masterReadyReg) { // input is ready
        when(io.slave.b.ready | ~slaveValidReg) {
          slaveValidNext     := io.master.b.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempSlaveValidNext := io.master.b.valid
          storeInputToTemp   := true.B
        }
      }.elsewhen(io.slave.b.ready) {
        slaveValidNext     := tempSlaveValidReg
        tempSlaveValidNext := false.B
        storeTempToOutput  := true.B
      }

      when(storeInputToOutput) {
        slaveReg := io.master.b.bits
      }.elsewhen(storeTempToOutput) {
        slaveReg := tempSlaveReg
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }

      when(storeInputToTemp) {
        slaveReg := io.master.b.bits
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }
  }
}
