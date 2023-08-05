package pipeline.simple

import chisel3._
import chisel3.util._
import spec._
import pipeline.simple.bundles.MainExeBranchInfoBundle
import pipeline.simple.ExeNdPort

class RegReadNdPort extends Bundle {
  val decodePorts       = Vec(Param.pipelineNum, Valid(new FetchInstDecodeNdPort))
  val mainExeBranchInfo = new MainExeBranchInfoBundle
}

object RegReadNdPort {
  def default = 0.U.asTypeOf(new RegReadNdPort)
}

class DispatchExeAdapter(pipelineNum: Int = Param.pipelineNum) extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new RegReadNdPort))
    val outMain   = Decoupled(new MainExeNdPort)
    val outSimple = Vec(pipelineNum - 1, Decoupled(new ExeNdPort))
    val peer      = Some(new SimpleDispatchPeerPort)
    val isFlush   = Input(Bool())
  })

  val peer = io.peer.get
  val outs = Seq(io.outMain) ++ io.outSimple

  io.in.ready := outs.map(_.ready).reduce(_ && _)
  outs.zip(io.in.bits.decodePorts).foreach {
    case (out, in) =>
      out.valid := in.valid && io.in.valid && io.in.valid
  }

  io.outMain.bits.branchInfo := io.in.bits.mainExeBranchInfo

  outs.lazyZip(io.in.bits.decodePorts).zipWithIndex.foreach {
    case ((out, in), idx) =>
      val readPorts  = peer.regReadPorts(idx)
      val occupyPort = peer.occupyPorts(idx)
      occupyPort.en    := in.valid && io.in.ready && io.in.valid && in.bits.decode.info.gprWritePort.en
      occupyPort.addr  := in.bits.decode.info.gprWritePort.addr
      occupyPort.robId := in.bits.instInfo.robId

      val regReadValid = WireDefault(true.B)
      readPorts.zip(in.bits.decode.info.gprReadPorts).foreach {
        case (dst, src) =>
          dst.addr := src.addr
          when(src.en && !dst.data.valid) {
            regReadValid := false.B
          }
      }

      when(!regReadValid) {
        io.in.ready := false.B
        outs.foreach(_.valid := false.B)
      }

      out.bits.leftOperand := readPorts(0).data.bits
      out.bits.rightOperand := Mux(
        in.bits.decode.info.isHasImm,
        in.bits.decode.info.imm,
        readPorts(1).data.bits
      )

      out.bits.exeSel         := in.bits.decode.info.exeSel
      out.bits.exeOp          := in.bits.decode.info.exeOp
      out.bits.gprWritePort   := in.bits.decode.info.gprWritePort
      out.bits.jumpBranchAddr := in.bits.decode.info.jumpBranchAddr
      out.bits.instInfo       := in.bits.instInfo
  }

  if (Param.usePmu) {
    peer.pmu.get <> DontCare
  }
}
