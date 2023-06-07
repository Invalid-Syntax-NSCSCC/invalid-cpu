package frontend.fetch

import chisel3._
import chisel3.util._
import common.bundles.{PassThroughPort, RfWriteNdPort}
import control.bundles.PipelineControlNdPort
import memory.bundles.TlbTransPort
import memory.enums.TlbMemType
import pipeline.mem.bundles.MemCsrNdPort
import pipeline.mem.enums.AddrTransType
import pipeline.writeback.bundles.InstInfoNdPort
import spec.Value.Csr
import spec.Width
import memory.bundles.MemResponseNdPort
import pipeline.common.BaseStage
import pipeline.dispatch.bundles.InstInfoBundle

class InstResNdPort extends Bundle {
  val isHasReq = Bool()
  val addr     = UInt(Width.Mem.addr)
  val isCached = Bool()
}

object InstResNdPort {
  def default: InstResNdPort = 0.U.asTypeOf(new InstResNdPort)
}

class InstResPeerPort extends Bundle {
  val res = Input(new MemResponseNdPort)
}

class InstEnqueuePort extends Bundle {
  val instInfo = new InstInfoBundle
}

class InstResStage
    extends BaseStage(
      new InstResNdPort,
      new InstInfoBundle,
      InstResNdPort.default,
      Some(new InstResPeerPort)
    ) {
  val peer = io.peer.get
  val out  = resultOutReg.bits

  // Fallback output
  out.pcAddr := selectedIn.addr
  out.inst   := 0.U(Width.inst)

  // Get read data
  val readData = WireDefault(peer.res.read.data)

  when(selectedIn.isHasReq) {
    out.inst := readData
  }

  // Whether memory access complete
  when(selectedIn.isHasReq) {
    isComputed := peer.res.isComplete
  }.otherwise {
    isComputed := false.B
  }

  // Submit result
  resultOutReg.valid := isComputed
}
