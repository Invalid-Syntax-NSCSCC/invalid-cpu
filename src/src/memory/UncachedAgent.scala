package memory

import axi.AxiMaster
import axi.bundles.AxiMasterInterface
import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import memory.bundles.MemAccessPort
import memory.enums.{UncachedAgentState => State}
import spec._

class UncachedAgent extends Module {
  val io = IO(new Bundle {
    val accessPort    = new MemAccessPort
    val axiMasterPort = new AxiMasterInterface
  })

  // Use naïve AXI master (because we don't need burst)
  val axiMaster = Module(new AxiMaster)

  // Configure AXI master (translate address and mask to address and AXI length)
  io.axiMasterPort      <> axiMaster.io.axi
  axiMaster.io.uncached := true.B
  val newReq       = WireDefault(false.B) // Fallback: No new request
  val selectedAddr = WireDefault(io.accessPort.req.client.addr) // Fallback: Current address
  val selectedData = WireDefault(io.accessPort.req.client.write.data) // Fallback: Current data
  val selectedRw   = WireDefault(io.accessPort.req.client.rw) // Fallback: Current read-write
  val selectedMask = WireDefault(io.accessPort.req.client.mask) // Fallback: Current mask
  axiMaster.io.newRequest := newReq
  axiMaster.io.addr := Cat(
    selectedAddr(Width.Mem._addr - 1, 2),
    MuxLookup(selectedMask, 0.U(2.W))(
      Seq(
        "b0001".U(4.W) -> 0.U(2.W),
        "b0010".U(4.W) -> 1.U(2.W),
        "b0100".U(4.W) -> 2.U(2.W),
        "b1000".U(4.W) -> 3.U(2.W),
        "b0011".U(4.W) -> 0.U(2.W),
        "b1100".U(4.W) -> 2.U(2.W),
        "b1111".U(4.W) -> 0.U(2.W)
      )
    )
  )
  axiMaster.io.dataIn := selectedData
  axiMaster.io.we     := selectedRw === ReadWriteSel.write
  axiMaster.io.wstrb  := selectedMask
  axiMaster.io.size := MuxLookup(selectedMask, Value.Axi.Size._4_B)(
    Seq(
      "b0001".U(4.W) -> Value.Axi.Size._1_B,
      "b0010".U(4.W) -> Value.Axi.Size._1_B,
      "b0100".U(4.W) -> Value.Axi.Size._1_B,
      "b1000".U(4.W) -> Value.Axi.Size._1_B,
      "b0011".U(4.W) -> Value.Axi.Size._2_B,
      "b1100".U(4.W) -> Value.Axi.Size._2_B,
      "b1111".U(4.W) -> Value.Axi.Size._4_B
    )
  )

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

  def handleRequest() = {
    io.accessPort.req.isReady := true.B
    when(io.accessPort.req.client.isValid) {
      // Persist the request
      lastReg.addr := io.accessPort.req.client.addr
      lastReg.data := io.accessPort.req.client.write.data
      lastReg.rw   := io.accessPort.req.client.rw
      lastReg.mask := io.accessPort.req.client.mask

      when(axiMaster.io.readyOut) {
        // Send new request
        newReq := true.B

        // Next state: Wait for response
        stateReg := State.waitRes
      }.otherwise {
        // Next state: Wait for AXI master ready
        stateReg := State.waitReady
      }
    }
  }

  switch(stateReg) {
    is(State.ready) {
      handleRequest()
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
//        io.accessPort.res.read.data := MuxLookup(lastReg.mask, axiMaster.io.dataOut)(
//          Seq(
//            "b0001".U(4.W) -> Cat(Seq.fill(4)(axiMaster.io.dataOut(7, 0))),
//            "b0010".U(4.W) -> Cat(Seq.fill(4)(axiMaster.io.dataOut(15, 8))),
//            "b0100".U(4.W) -> Cat(Seq.fill(4)(axiMaster.io.dataOut(23, 16))),
//            "b1000".U(4.W) -> Cat(Seq.fill(4)(axiMaster.io.dataOut(31, 24))),
//            "b0011".U(4.W) -> Cat(Seq.fill(2)(axiMaster.io.dataOut(15, 0))),
//            "b1100".U(4.W) -> Cat(Seq.fill(2)(axiMaster.io.dataOut(31, 16))),
//            "b1111".U(4.W) -> axiMaster.io.dataOut
//          )
//        )
        io.accessPort.res.isFailed := false.B // TODO: The simple AXI master cannot detect failing

        // Next state: Ready for new request
        stateReg := State.ready

        // Also now it is equivilent to ready state
        handleRequest()
      }
    }
  }
}
