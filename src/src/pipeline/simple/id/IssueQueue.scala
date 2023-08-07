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

class RegReadNdPort extends Bundle {
  val instInfo = new InstInfoNdPort
  val pc       = UInt(Width.Reg.data)
  val decode   = new DecodeOutNdPort
}

class IssueQueue(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Module {

  require(issueNum == pipelineNum)
  val io = IO(new Bundle {
    val isFlush = Input(Bool())
    val ins = Vec(
      issueNum,
      Flipped(Decoupled(new RegReadNdPort))
    )

    val dequeuePorts = new Bundle {
      val mainExePort    = Decoupled(new MainExeNdPort)
      val simpleExePorts = Vec(pipelineNum - 1, Decoupled(new ExeNdPort))
    }

    val queryPcPort = Flipped(new QueryPcBundle)

    val regReadPorts = Vec(Param.issueInstInfoMaxNum, Vec(Param.regFileReadNum, Flipped(new RegReadPort)))
    val occupyPorts  = Output(Vec(Param.issueInstInfoMaxNum, new RegOccupyNdPort))

    val wakeUpPorts       = Input(Vec(pipelineNum + 1, new RegWakeUpNdPort))
    val pmu_dispatchInfos = Option.when(Param.usePmu)(Output(Vec(Param.issueInstInfoMaxNum, new PmuDispatchInfoBundle)))

  })

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
        // val prevGprWrite = prev.bits.decodePort.decode.info.gprWritePort

        // data dependence
        // dst.bits.decodePort.decode.info.gprReadPorts.foreach { r =>
        //   when(r.en && prevGprWrite.en && r.addr === prevGprWrite.addr) {
        //     dst.valid := false.B
        //     src.ready := false.B
        //   }
        // }

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
  mainRSEnqPort.bits.mainExeBranchInfo.pc              := io.ins.head.bits.pc
  io.queryPcPort.ftqId                                 := io.ins.head.bits.instInfo.ftqInfo.ftqId + 1.U
  mainRSEnqPort.bits.mainExeBranchInfo.predictJumpAddr := io.queryPcPort.pc
  mainRSEnqPort.bits.mainExeBranchInfo.isBranch        := io.ins.head.bits.decode.info.isBranch
  mainRSEnqPort.bits.mainExeBranchInfo.branchType      := io.ins.head.bits.decode.info.branchType

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
                // wakeUp.addr === decodeRead.addr &&
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
      out.bits.exeOp          := rs.bits.decodePort.instInfo.exeOp
      out.bits.exeSel         := rs.bits.decodePort.decode.info.exeSel
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
