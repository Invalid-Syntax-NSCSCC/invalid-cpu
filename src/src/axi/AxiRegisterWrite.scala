package axi

import axi.bundles.MasterRegisterWrite
import axi.types.RegType._
import chisel3._
import spec._

class AxiRegisterWrite(val idWidth: Int, val awRegType: RegType, val wRegType: RegType, val bRegType: RegType)
    extends Module {
  val io = IO(new Bundle {
    val slave  = Flipped(new MasterRegisterWrite(idWidth))
    val master = new MasterRegisterWrite(idWidth)
  })

  // AW channel
  awRegType match {
    case BYPASS =>
      io.master.aw <> io.slave.aw
      io.master.aw.bits.user := (if (Axi.Crossbar.awuserEnable) io.slave.aw.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val masterReg          = Reg(chiselTypeOf(io.master.aw.bits))
      val masterValidNextReg = Reg(Bool())
      val masterValidReg     = RegNext(masterValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())

      val slaveReadyEarly = !masterValidNextReg // enable ready input next cycle if output buffer will be empty
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.aw.ready      := slaveReadyReg
      io.master.aw.bits      := masterReg
      io.master.aw.bits.user := (if (Axi.Crossbar.awuserEnable) masterReg.user else 0.U)
      io.master.aw.valid     := masterValidReg

      // transfer sink ready state to source
      when(slaveReadyReg) {
        masterValidNextReg := io.slave.aw.valid
        storeInputToOutput := true.B
      }.elsewhen(io.master.aw.ready) {
        masterValidNextReg := false.B
        storeInputToOutput := false.B
      }.otherwise {
        masterValidNextReg := masterValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.aw.bits
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val masterReg          = Reg(chiselTypeOf(io.master.aw.bits))
      val masterValidNextReg = Reg(Bool())
      val masterValidReg     = RegNext(masterValidNextReg, false.B)

      val tempMasterReg          = Reg(chiselTypeOf(io.master.aw.bits))
      val tempMasterValidNextReg = Reg(Bool())
      val tempMasterValidReg     = RegNext(tempMasterValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())
      val storeInputToTemp   = Reg(Bool())
      val storeTempToOutput  = Reg(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val slaveReadyEarly = io.master.aw.ready | (!tempMasterValidReg & (!masterValidReg | !io.slave.aw.valid))
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.aw.ready      := slaveReadyReg
      io.master.aw.bits      := masterReg
      io.master.aw.bits.user := (if (Axi.Crossbar.awuserEnable) masterReg.user else 0.U)
      io.master.aw.valid     := masterValidReg

      // transfer sink ready state to source
      masterValidNextReg     := masterValidReg
      tempMasterValidNextReg := tempMasterValidReg
      storeInputToOutput     := false.B
      storeInputToTemp       := false.B
      storeTempToOutput      := false.B
      when(slaveReadyReg) { // input is ready
        when(io.master.aw.ready | ~masterValidReg) {
          // output is ready or currently not valid, transfer data to output
          masterValidNextReg := io.slave.aw.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempMasterValidNextReg := io.slave.aw.valid
          storeInputToTemp       := true.B
        }
      }.elsewhen(io.master.aw.ready) {
        // input is not ready, but output is ready
        masterValidNextReg     := tempMasterValidReg
        tempMasterValidNextReg := false.B
        storeTempToOutput      := true.B
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
      io.master.w.bits.user := (if (Axi.Crossbar.wuserEnable) io.slave.w.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val masterReg          = Reg(chiselTypeOf(io.master.w.bits))
      val masterValidNextReg = Reg(Bool())
      val masterValidReg     = RegNext(masterValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())

      val slaveReadyEarly = !masterValidNextReg // enable ready input next cycle if output buffer will be empty
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.w.ready      := slaveReadyReg
      io.master.w.bits      := masterReg
      io.master.w.bits.user := (if (Axi.Crossbar.wuserEnable) masterReg.user else 0.U)
      io.master.w.valid     := masterValidReg

      // transfer sink ready state to source
      when(slaveReadyReg) {
        masterValidNextReg := io.slave.w.valid
        storeInputToOutput := true.B
      }.elsewhen(io.master.w.ready) {
        masterValidNextReg := false.B
        storeInputToOutput := false.B
      }.otherwise {
        masterValidNextReg := masterValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        masterReg := io.slave.w.bits
      }.otherwise {
        masterReg := 0.U.asTypeOf(masterReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val masterReg          = Reg(chiselTypeOf(io.master.w.bits))
      val masterValidNextReg = Reg(Bool())
      val masterValidReg     = RegNext(masterValidNextReg, false.B)

      val tempMasterReg          = Reg(chiselTypeOf(io.master.w.bits))
      val tempMasterValidNextReg = Reg(Bool())
      val tempMasterValidReg     = RegNext(tempMasterValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())
      val storeInputToTemp   = Reg(Bool())
      val storeTempToOutput  = Reg(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val slaveReadyEarly = io.master.w.ready | (!tempMasterValidReg & (!masterValidReg | !io.slave.w.valid))
      val slaveReadyReg   = RegNext(slaveReadyEarly, false.B)

      // datapath control
      io.slave.w.ready      := slaveReadyReg
      io.master.w.bits      := masterReg
      io.master.w.bits.user := (if (Axi.Crossbar.wuserEnable) masterReg.user else 0.U)
      io.master.w.valid     := masterValidReg

      // transfer sink ready state to source
      masterValidNextReg     := masterValidReg
      tempMasterValidNextReg := tempMasterValidReg
      storeInputToOutput     := false.B
      storeInputToTemp       := false.B
      storeTempToOutput      := false.B
      when(slaveReadyReg) { // input is ready
        when(io.master.w.ready | ~masterValidReg) {
          // output is ready or currently not valid, transfer data to output
          masterValidNextReg := io.slave.w.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempMasterValidNextReg := io.slave.w.valid
          storeInputToTemp       := true.B
        }
      }.elsewhen(io.master.w.ready) {
        // input is not ready, but output is ready
        masterValidNextReg     := tempMasterValidReg
        tempMasterValidNextReg := false.B
        storeTempToOutput      := true.B
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
      io.slave.b.bits.user := (if (Axi.Crossbar.buserEnable) io.master.b.bits.user else 0.U)
    case SIMPLE_BUFFER => // inserts bubble cycles
      val slaveReg          = Reg(chiselTypeOf(io.slave.b.bits))
      val slaveValidNextReg = Reg(Bool())
      val slaveValidReg     = RegNext(slaveValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())

      val masterReadyEarly = !slaveValidNextReg // enable ready input next cycle if output buffer will be empty
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.b.ready    := masterReadyReg
      io.slave.b.bits      := slaveReg
      io.slave.b.bits.user := (if (Axi.Crossbar.buserEnable) slaveReg.user else 0.U)
      io.slave.b.valid     := slaveValidReg

      // transfer sink ready state to source
      when(masterReadyReg) {
        slaveValidNextReg  := io.master.b.valid
        storeInputToOutput := true.B
      }.elsewhen(io.slave.b.ready) {
        slaveValidNextReg  := false.B
        storeInputToOutput := false.B
      }.otherwise {
        slaveValidNextReg  := slaveValidReg
        storeInputToOutput := false.B
      }

      when(storeInputToOutput) {
        slaveReg := io.master.b.bits
      }.otherwise {
        slaveReg := 0.U.asTypeOf(slaveReg)
      }
    case SKID_BUFFER => // no bubble cycles
      val slaveReg          = Reg(chiselTypeOf(io.slave.b.bits))
      val slaveValidNextReg = Reg(Bool())
      val slaveValidReg     = RegNext(slaveValidNextReg, false.B)

      val tempSlaveReg          = Reg(chiselTypeOf(io.slave.b.bits))
      val tempSlaveValidNextReg = Reg(Bool())
      val tempSlaveValidReg     = RegNext(tempSlaveValidNextReg, false.B)

      val storeInputToOutput = Reg(Bool())
      val storeInputToTemp   = Reg(Bool())
      val storeTempToOutput  = Reg(Bool())

      // enable ready input next cycle if output is ready or the temp reg will not be filled on the next cycle (output reg empty or no input)
      val masterReadyEarly = io.slave.b.ready | (!tempSlaveValidReg & (!slaveValidReg | !io.master.b.valid))
      val masterReadyReg   = RegNext(masterReadyEarly, false.B)

      // datapath control
      io.master.b.ready    := masterReadyReg
      io.slave.b.bits      := slaveReg
      io.slave.b.bits.user := (if (Axi.Crossbar.buserEnable) slaveReg.user else 0.U)
      io.slave.b.valid     := slaveValidReg

      // transfer sink ready state to source
      slaveValidNextReg     := slaveValidReg
      tempSlaveValidNextReg := tempSlaveValidReg
      storeInputToOutput    := false.B
      storeInputToTemp      := false.B
      storeTempToOutput     := false.B
      when(masterReadyReg) { // input is ready
        when(io.slave.b.ready | ~slaveValidReg) {
          slaveValidNextReg  := io.master.b.valid
          storeInputToOutput := true.B
        }.otherwise {
          tempSlaveValidNextReg := io.master.b.valid
          storeInputToTemp      := true.B
        }
      }.elsewhen(io.slave.b.ready) {
        slaveValidNextReg     := tempSlaveValidReg
        tempSlaveValidNextReg := false.B
        storeTempToOutput     := true.B
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
