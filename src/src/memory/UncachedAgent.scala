package memory

import axi.AxiMaster
import axi.bundles.AxiMasterPort
import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import memory.bundles.{MemAccessPort, MemRequestNdPort, MemResponseNdPort}
import memory.enums.{UncachedAgentState => State}
import spec._

class UncachedAgent extends Module {
  val io = IO(new Bundle {
    val accessPort    = new MemAccessPort
    val axiMasterPort = new AxiMasterPort
  })

  // Use naïve AXI master (because we don't need burst)
  val axiMaster = Module(new AxiMaster)

  // Configure AXI master
  io.axiMasterPort      <> axiMaster.io.axi
  axiMaster.io.uncached := true.B
  axiMaster.io.size     := Value.Axi.Size.get(4)
  val newReq       = WireDefault(false.B) // Fallback: No new request
  val selectedAddr = WireDefault(io.accessPort.req.client.addr) // Fallback: Current address
  val selectedData = WireDefault(io.accessPort.req.client.write.data) // Fallback: Current data
  val selectedRw   = WireDefault(io.accessPort.req.client.rw) // Fallback: Current read-write
  val selectedMask = WireDefault(io.accessPort.req.client.write.mask) // Fallback: Current mask
  axiMaster.io.newRequest := newReq
  axiMaster.io.addr       := selectedAddr
  axiMaster.io.dataIn     := selectedData
  axiMaster.io.we         := selectedRw === ReadWriteSel.write
  axiMaster.io.wstrb      := selectedMask

  // Save data for later use
  val lastReg = Reg(new Bundle {
    val addr = UInt(Width.Mem.addr)
    val data = UInt(Width.Mem.data)
    val rw   = ReadWriteSel()
    val mask = UInt((Width.Mem._data / byteLength).W)
  })
  lastReg := lastReg // Fallback: Keep data

  // State
  val stateReg = RegInit(State.ready)
  stateReg := stateReg // Fallback: Keep state

  // Fallback
  io.accessPort.req.isReady    := false.B // Not ready
  io.accessPort.res.isComplete := false.B // Not complete
  io.accessPort.res.isFailed   := DontCare // Successful
  io.accessPort.res.read.data  := DontCare

  switch(stateReg) {
    is(State.ready) {
      io.accessPort.req.isReady := true.B
      when(io.accessPort.req.client.isValid) {
        when(axiMaster.io.readyOut) {
          // Send new request
          newReq := true.B

          // Next state: Wait for response
          stateReg := State.waitRes
        }.otherwise {
          // Persist the request
          lastReg.addr := io.accessPort.req.client.addr
          lastReg.data := io.accessPort.req.client.write.data
          lastReg.rw   := io.accessPort.req.client.rw
          lastReg.mask := io.accessPort.req.client.write.mask

          // Next state: Wait for AXI master ready
          stateReg := State.waitReady
        }
      }
    }
    is(State.waitReady) {
      when(axiMaster.io.readyOut) {
        // Send the persisted request
        newReq       := true.B
        selectedAddr := lastReg.addr
        selectedData := lastReg.data
        selectedRw   := lastReg.rw
        selectedMask := lastReg.mask

        // Next state: Wait for response
        stateReg := State.waitRes
      }
    }
    is(State.waitRes) {
      when(axiMaster.io.validOut) {
        // Send response
        io.accessPort.res.isComplete := true.B
        io.accessPort.res.read.data  := axiMaster.io.dataOut
        io.accessPort.res.isFailed   := false.B // TODO: The simple AXI master cannot detect failing

        // Next state: Ready for new request
        stateReg := State.ready
      }
    }
  }
}