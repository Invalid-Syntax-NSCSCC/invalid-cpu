package pipeline.simple.id

import chisel3._
import pipeline.simple.bundles._
import pipeline.simple.id.rs.ReservationStation
import spec._
import pipeline.simple.id.rs.IoReservationStation

// class RegReadNdPort extends Bundle {
//   val instInfo = new InstInfoNdPort
//   val pc       = UInt(Width.Reg.data)
//   val decode   = new DecodeOutNdPort
// }

class CompressIssueQueue(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends BaseIssueQueue(issueNum, pipelineNum) {

  require(issueNum == pipelineNum)

  private val rsLength = 4

  val mainRS = Module(
    new IoReservationStation(rsLength, new MainRSBundle, 0.U.asTypeOf(new MainRSBundle))
  )

  val simpleRSs = Seq.fill(pipelineNum - 1)(
    Module(
      new IoReservationStation(rsLength, new RSBundle, 0.U.asTypeOf(new RSBundle))
    )
  )

  // reg read
  io.regReadPorts.zip(io.ins).foreach {
    case (readPorts, in) =>
      readPorts.zip(in.bits.decode.info.gprReadPorts).foreach {
        case (dst, src) =>
          dst.addr := src.addr
      }
  }

  // reservation station

  val mainRSEnqPort    = mainRS.io.enqueuePort
  val simpleRSEnqPorts = simpleRSs.map(_.io.enqueuePort)

  mainRS.io.isFlush := io.isFlush
  mainRS.io.writebacks.zip(io.wakeUpPorts).foreach {
    case (dst, src) =>
      dst := src
  }
  simpleRSs.zip(simpleRSEnqPorts).foreach {
    case (rs, enq) =>
      rs.io.isFlush := io.isFlush
      rs.io.writebacks.zip(io.wakeUpPorts).foreach {
        case (dst, src) =>
          dst := src
      }
  }

  val rsEnqPorts = Seq(mainRSEnqPort) ++ simpleRSEnqPorts
  val rss        = Seq(mainRS) ++ simpleRSs

  // occupy
  io.occupyPorts.lazyZip(rsEnqPorts).foreach {
    case (occupy, enq) =>
      occupy.en    := enq.valid && enq.ready && enq.bits.decodePort.decode.info.gprWritePort.en
      occupy.addr  := enq.bits.decodePort.decode.info.gprWritePort.addr
      occupy.robId := enq.bits.decodePort.instInfo.robId
  }

  // determine enqueue
  rsEnqPorts.lazyZip(io.ins).zipWithIndex.foreach {
    case ((dst, src), idx) =>
      src.ready := dst.ready
      dst.valid := src.valid

      rsEnqPorts.take(idx).foreach { prev =>
        // issue in order
        when(!(prev.ready && prev.valid)) {
          dst.valid := false.B
          src.ready := false.B
        }
      }

      // should issue in main
      if (idx != 0) {
        when(src.bits.decode.info.isIssueMainPipeline) {
          dst.valid := false.B
          src.ready := false.B
        }
      }
  }

  // reservation station enqueue bits
  val fallThroughPc = io.ins.head.bits.pc + 4.U
  val predictPc     = io.queryPcPort.pc
  mainRSEnqPort.bits.mainExeBranchInfo.fallThroughPc             := fallThroughPc
  mainRSEnqPort.bits.mainExeBranchInfo.fallThroughPredictCorrect := fallThroughPc === predictPc
  mainRSEnqPort.bits.mainExeBranchInfo.immPredictCorrect := io.ins.head.bits.decode.info.jumpBranchAddr === predictPc
  mainRSEnqPort.bits.mainExeBranchInfo.predictSubImm     := predictPc - io.ins.head.bits.decode.info.jumpBranchAddr
  io.queryPcPort.ftqId                                   := io.ins.head.bits.instInfo.ftqInfo.ftqId + 1.U
  mainRSEnqPort.bits.mainExeBranchInfo.predictJumpAddr   := predictPc
  mainRSEnqPort.bits.mainExeBranchInfo.isBranch          := io.ins.head.bits.decode.info.isBranch
  mainRSEnqPort.bits.mainExeBranchInfo.branchType        := io.ins.head.bits.decode.info.branchType

  rsEnqPorts.lazyZip(io.ins).lazyZip(io.regReadPorts).zipWithIndex.foreach {
    case ((rs, in, readRes), index) =>
      rs.bits.decodePort.instInfo := in.bits.instInfo
      rs.bits.decodePort.decode   := in.bits.decode
      in.bits.decode.info.gprReadPorts.lazyZip(rs.bits.regReadResults).lazyZip(readRes).foreach {
        case (readInfo, dst, readRes) =>
          dst.valid := !(readInfo.en && !readRes.data.valid)
          dst.bits  := readRes.data.bits

          // data dependence
          rsEnqPorts.take(index).zipWithIndex.foreach {
            case (prev, prevIdx) =>
              when(
                readInfo.en &&
                  prev.bits.decodePort.decode.info.gprWritePort.en &&
                  prev.bits.decodePort.decode.info.gprWritePort.addr === readInfo.addr
              ) {
                dst.valid := false.B
                dst.bits  := prev.bits.decodePort.instInfo.robId
              }
          }
      }
  }

  // output
  io.dequeuePorts.mainExePort.bits.branchInfo := mainRS.io.dequeuePort.bits.mainExeBranchInfo
  (Seq(io.dequeuePorts.mainExePort) ++ io.dequeuePorts.simpleExePorts).zip(rss.map(_.io.dequeuePort)).foreach {
    case (out, rs) =>
      val regReadResults = rs.bits.regReadResults

      out.valid               := rs.valid
      rs.ready                := out.ready
      out.bits.gprWritePort   := rs.bits.decodePort.decode.info.gprWritePort
      out.bits.instInfo       := rs.bits.decodePort.instInfo
      out.bits.jumpBranchAddr := rs.bits.decodePort.decode.info.jumpBranchAddr
      out.bits.leftOperand    := regReadResults(0).bits
      out.bits.rightOperand := Mux(
        rs.bits.decodePort.decode.info.isHasImm,
        rs.bits.decodePort.decode.info.imm,
        regReadResults(1).bits
      )
  }

  if (Param.usePmu) {
    val pmuInfos = io.pmu_dispatchInfos.get
    pmuInfos.lazyZip(rss).lazyZip(Seq(io.dequeuePorts.mainExePort) ++ io.dequeuePorts.simpleExePorts).foreach {
      case (pmu, rs, out) =>
        pmu.isIssueInst              := out.valid && out.ready && !io.isFlush
        pmu.bubbleFromFrontend       := !rs.io.dequeuePort.valid && !io.isFlush
        pmu.bubbleFromBackend        := out.valid && !out.ready && !io.isFlush
        pmu.bubbleFromDataDependence := rs.io.dequeuePort.valid && !out.valid && !io.isFlush
    }
  }

}
