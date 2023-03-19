package axi

import axi.bundles._
import chisel3._
import spec._

class AxiCrossbar extends Module {
  val io = IO(new Bundle {
    val slaves  = Vec(Param.Count.Axi.slave, new SlaveCrossbar)
    val masters = Vec(Param.Count.Axi.master, new MasterCrossbar)
  })

  val axiCrossbarWrite = Module(new AxiCrossbarWrite())
  for ((src, dst) <- axiCrossbarWrite.io.slaves zip io.slaves) {
    src <> dst.write
  }
  for ((src, dst) <- axiCrossbarWrite.io.masters zip io.masters) {
    src <> dst.write
  }

  val axiCrossbarRead = Module(new AxiCrossbarRead())
  for ((src, dst) <- axiCrossbarRead.io.slaves zip io.slaves) {
    src <> dst.read
  }
  for ((src, dst) <- axiCrossbarRead.io.masters zip io.masters){
    src <> dst.read
  }
}
