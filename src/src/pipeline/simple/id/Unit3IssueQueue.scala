package pipeline.simple.id

import chisel3._
import chisel3.util._
import spec._
import frontend.bundles.QueryPcBundle
import pipeline.simple.bundles._
import pipeline.simple.MainExeNdPort
import pipeline.simple.ExeNdPort
import common.MultiQueue
import pipeline.simple.pmu.bundles.PmuDispatchInfoBundle
import common.bundles.RfAccessInfoNdPort
import utils.MultiMux1
import pipeline.simple.decode.bundles.DecodeOutNdPort

// class RegReadNdPort extends Bundle {
//   val instInfo = new InstInfoNdPort
//   val pc       = UInt(Width.Reg.data)
//   val decode   = new DecodeOutNdPort
// }

class Unit3IssueQueue(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends BaseIssueQueue(issueNum, pipelineNum) {

  require(issueNum == 2 && pipelineNum == 3)

  private val rsLength = 4

  val mainRS = Module(
    new MultiQueue(rsLength, 1, 1, new MainRSBundle, 0.U.asTypeOf(new MainRSBundle), writeFirst = false)
  )
  val simpleRSs = Seq.fill(pipelineNum - 1)(
    Module(
      new MultiQueue(rsLength, 1, 1, new RSBundle, 0.U.asTypeOf(new RSBundle), writeFirst = false)
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

  val mainRSEnqPort    = mainRS.io.enqueuePorts.head
  val simpleRSEnqPorts = simpleRSs.map(_.io.enqueuePorts.head)

  mainRS.io.setPorts.zip(mainRS.io.elems).foreach {
    case (set, elem) =>
      set.bits  := elem
      set.valid := true.B
  }
  mainRS.io.isFlush := io.isFlush
  simpleRSs.zip(simpleRSEnqPorts).foreach {
    case (rs, enq) =>
      rs.io.setPorts.zip(rs.io.elems).foreach {
        case (set, elem) =>
          set.bits  := elem
          set.valid := true.B
      }
      rs.io.isFlush := io.isFlush
  }

  val rsEnqPorts = Seq(mainRSEnqPort) ++ simpleRSEnqPorts
  val rss        = Seq(mainRS) ++ simpleRSs

  // occupy
  io.occupyPorts.lazyZip(io.ins).foreach {
    case (occupy, in) =>
      occupy.en    := in.valid && in.ready && in.bits.decode.info.gprWritePort.en
      occupy.addr  := in.bits.decode.info.gprWritePort.addr
      occupy.robId := in.bits.instInfo.robId
  }

  // determine enqueue

  rsEnqPorts.foreach(_.valid := false.B)
  rsEnqPorts.foreach(_.bits := DontCare)
  io.ins.foreach(_.ready := false.B)

  val enqIndices = Wire(Vec(issueNum, UInt(log2Ceil(pipelineNum).W)))
  enqIndices.foreach(_ := 3.U)
  io.ins.zipWithIndex.foreach {
    case (in, src_idx) =>
      if (src_idx == 0) {
        when(in.bits.decode.info.isIssueMainPipeline) {
          in.ready              := rsEnqPorts.head.ready
          rsEnqPorts.head.valid := in.valid
          enqIndices(src_idx)   := 0.U
        }.otherwise {
          when(rsEnqPorts(1).ready) {
            in.ready            := true.B
            rsEnqPorts(1).valid := in.valid
            enqIndices(src_idx) := 1.U
          }.elsewhen(rsEnqPorts(2).ready) {
            in.ready            := true.B
            rsEnqPorts(2).valid := in.valid
            enqIndices(src_idx) := 2.U
          }
        }
      } else {
        val nonblock = io.ins
          .take(src_idx)
          .map { prevIn => prevIn.valid && prevIn.ready }
          .reduce(_ && _) && !in.bits.decode.info.isIssueMainPipeline

        when(nonblock) {
          val ens = WireDefault(VecInit(Seq.fill(pipelineNum)(false.B)))
          Seq.range(1, pipelineNum).foreach { dst_idx =>
            when(dst_idx.U > enqIndices.head && rsEnqPorts(dst_idx).ready) {
              ens(dst_idx) := true.B
            }
          }
          when(ens(1)) {
            in.ready            := true.B
            rsEnqPorts(1).valid := in.valid
            enqIndices(src_idx) := 1.U
          }.elsewhen(ens(2)) {
            in.ready            := true.B
            rsEnqPorts(2).valid := in.valid
            enqIndices(src_idx) := 2.U
          }

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

  (io.ins).lazyZip(io.regReadPorts).zipWithIndex.foreach {
    case ((in, readRes), index) =>
      val selectDstIdx = enqIndices(index)
      // val rs      = rsEnqPorts(dst_idx)
      rsEnqPorts.zipWithIndex.foreach {
        case (rs, dst_idx) =>
          if (dst_idx >= index) {
            when(selectDstIdx === dst_idx.U) {
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
          }
      }
  }

  // wake up
  rss.foreach { rs =>
    rs.io.setPorts.zip(rs.io.elems).foreach {
      case (set, elem) =>
        set.bits.regReadResults.lazyZip(elem.regReadResults).lazyZip(elem.decodePort.decode.info.gprReadPorts).foreach {
          case (setRegData, elemData, decodeRead) =>
            setRegData := elemData

            val mux = Module(new MultiMux1(pipelineNum + 1, UInt(Width.Reg.data), zeroWord))
            mux.io.inputs.zip(io.wakeUpPorts).foreach {
              case (input, wakeUp) =>
                input.valid := wakeUp.en &&
                  wakeUp.addr === decodeRead.addr &&
                  wakeUp.robId(Param.Width.Rob._id - 1, 0) === elemData.bits(Param.Width.Rob._id - 1, 0)
                input.bits := wakeUp.data
            }
            when(!elemData.valid && mux.io.output.valid) {
              setRegData.valid := true.B
              setRegData.bits  := mux.io.output.bits
            }
        }
    }
  }

  // output
  io.dequeuePorts.mainExePort.bits.branchInfo := mainRS.io.dequeuePorts.head.bits.mainExeBranchInfo
  (Seq(io.dequeuePorts.mainExePort) ++ io.dequeuePorts.simpleExePorts).zip(rss.map(_.io.dequeuePorts.head)).foreach {
    case (out, rs) =>
      val regReadResults = WireDefault(rs.bits.regReadResults)

      regReadResults.lazyZip(rs.bits.regReadResults).lazyZip(rs.bits.decodePort.decode.info.gprReadPorts).foreach {
        case (setRegData, elemData, decodeRead) =>
          val mux = Module(new MultiMux1(pipelineNum + 1, UInt(Width.Reg.data), zeroWord))
          mux.io.inputs.zip(io.wakeUpPorts).foreach {
            case (input, wakeUp) =>
              input.valid := wakeUp.en &&
                wakeUp.robId(Param.Width.Rob._id - 1, 0) === elemData.bits(Param.Width.Rob._id - 1, 0)
              input.bits := wakeUp.data
          }
          when(!elemData.valid && mux.io.output.valid) {
            setRegData.valid := true.B
            setRegData.bits  := mux.io.output.bits
          }

      }

      val deqEn = regReadResults.map(_.valid).reduce(_ && _)
      out.valid               := rs.valid && deqEn
      rs.ready                := out.ready && deqEn
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
        pmu.bubbleFromFrontend       := !rs.io.dequeuePorts.head.valid && !io.isFlush
        pmu.bubbleFromBackend        := out.valid && !out.ready && !io.isFlush
        pmu.bubbleFromDataDependence := rs.io.dequeuePorts.head.valid && !out.valid && !io.isFlush
    }
  }

}
