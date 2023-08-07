package pipeline.simple.id

import chisel3._
import chisel3.util._
import common.DistributedQueue
import common.bundles.BackendRedirectPcNdPort
import control.enums.ExceptionPos
import pipeline.common.bundles.{FetchInstInfoBundle, InstQueueEnqNdPort, PcInstBundle}
import pipeline.simple.decode._
import pipeline.simple.decode.bundles._
import spec._
import frontend.bundles.QueryPcBundle
import pipeline.simple.bundles._
import common.MultiQueue
import pipeline.simple.id.FetchInstDecodeNdPort
import pipeline.simple.MainExeNdPort
import pipeline.simple.ExeNdPort
import utils.MultiMux1
import pipeline.simple.pmu.bundles.PmuDispatchInfoBundle

// assert: enqueuePorts总是最低的几位有效
class IssueStage(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Module {
  val io = IO(new Bundle {
    val isFrontendFlush = Input(Bool())
    val isBackendFlush  = Input(Bool())

    val enqueuePorts = Vec(
      issueNum,
      Flipped(Decoupled(new FetchInstInfoBundle))
    )

    // `InstQueue` -> `IssueStage`
    val dequeuePorts = new Bundle {
      val mainExePort    = Decoupled(new MainExeNdPort)
      val simpleExePorts = Vec(pipelineNum - 1, Decoupled(new ExeNdPort))
    }

    val idleBlocking = Input(Bool())
    val hasInterrupt = Input(Bool())

    val robIdRequests = Vec(issueNum, Flipped(new RobRequestPort))

    val queryPcPort = Flipped(new QueryPcBundle)

    val regReadPorts = Vec(Param.issueInstInfoMaxNum, Vec(Param.regFileReadNum, Flipped(new RegReadPort)))
    val occupyPorts  = Output(Vec(Param.issueInstInfoMaxNum, new RegOccupyNdPort))

    val wakeUpPorts = Input(Vec(pipelineNum + 1, new RegWakeUpNdPort))

    val plv = Input(UInt(2.W))

    val pmu_dispatchInfos = Option.when(Param.usePmu)(Output(Vec(Param.issueInstInfoMaxNum, new PmuDispatchInfoBundle)))
  })
  require(issueNum == pipelineNum)

  private val rsLength = 2

  val isIdle = RegInit(false.B)
  when(io.hasInterrupt) {
    isIdle := false.B
  }.elsewhen(io.idleBlocking) {
    isIdle := true.B
  }

  // Decode
  val decodeInstInfos = VecInit(io.enqueuePorts.map(_.bits))

  // Select a decoder

  val decoders = Seq.fill(issueNum)(Module(new DecodeUnit))

  decoders.zip(decodeInstInfos).foreach {
    case (decoder, decodeInstInfo) =>
      decoder.io.in := decodeInstInfo
  }

  val selectedDecoders = decoders.map(_.io.out)

  val mainRS = Module(
    new MultiQueue(rsLength, 1, 1, new MainRSBundle, 0.U.asTypeOf(new MainRSBundle), writeFirst = false)
  )
  val simpleRSs = Seq.fill(pipelineNum - 1)(
    Module(
      new MultiQueue(rsLength, 1, 1, new RSBundle, 0.U.asTypeOf(new RSBundle), writeFirst = false)
    )
  )

  val mainRSEnqPort    = mainRS.io.enqueuePorts.head
  val simpleRSEnqPorts = simpleRSs.map(_.io.enqueuePorts.head)

  mainRS.io.setPorts.zip(mainRS.io.elems).foreach {
    case (set, elem) =>
      set.bits  := elem
      set.valid := true.B
  }
  mainRS.io.isFlush := io.isBackendFlush
  simpleRSs.zip(simpleRSEnqPorts).foreach {
    case (rs, enq) =>
      rs.io.setPorts.zip(rs.io.elems).foreach {
        case (set, elem) =>
          set.bits  := elem
          set.valid := true.B
      }
      rs.io.isFlush := io.isBackendFlush
  }

  val rsEnqPorts = Seq(mainRSEnqPort) ++ simpleRSEnqPorts
  val rss        = Seq(mainRS) ++ simpleRSs

  // reg read
  io.regReadPorts.zip(rsEnqPorts).foreach {
    case (readPorts, rsEnqPort) =>
      readPorts.zip(rsEnqPort.bits.decodePort.decode.info.gprReadPorts).foreach {
        case (dst, src) =>
          dst.addr := src.addr
      }
  }

  // occupy
  io.occupyPorts.lazyZip(rsEnqPorts).lazyZip(io.robIdRequests).foreach {
    case (occupy, enq, robIdReq) =>
      occupy.en    := enq.valid && enq.ready && enq.bits.decodePort.decode.info.gprWritePort.en
      occupy.addr  := enq.bits.decodePort.decode.info.gprWritePort.addr
      occupy.robId := robIdReq.result.bits
  }

  val isBlockDequeueReg = RegInit(false.B)
  when(io.isBackendFlush) {
    isBlockDequeueReg := false.B
  }.elsewhen(
    io.isFrontendFlush
      || (
        rsEnqPorts.map { port =>
          port.valid && port.ready && (
            port.bits.decodePort.decode.info.needRefetch ||
              port.bits.decodePort.instInfo.exceptionPos =/= ExceptionPos.none
          )
        }.reduce(_ || _)
      )
  ) {
    isBlockDequeueReg := true.B
  }

  // rob id request

  rsEnqPorts.lazyZip(io.enqueuePorts).lazyZip(io.robIdRequests).zipWithIndex.foreach {
    case ((dst, src, robIdReq), idx) =>
      src.ready := dst.ready
      dst.valid := src.valid

      robIdReq.request.valid       := src.valid && src.ready
      robIdReq.request.bits.pcAddr := src.bits.pcAddr
      robIdReq.request.bits.inst   := src.bits.inst

      // block
      when(
        isBlockDequeueReg ||
          isIdle ||
          // io.isFrontendFlush ||
          !robIdReq.result.valid
      ) {
        dst.valid := false.B
        src.ready := false.B
      }

      rsEnqPorts.take(idx).foreach { prev =>
        val prevGprWrite = prev.bits.decodePort.decode.info.gprWritePort

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
        when(dst.bits.decodePort.decode.info.isIssueMainPipeline) {
          dst.valid := false.B
          src.ready := false.B
        }
      }
  }

  // reservation station enqueue bits
  mainRSEnqPort.bits.mainExeBranchInfo.pc              := decodeInstInfos.head.pcAddr
  io.queryPcPort.ftqId                                 := decodeInstInfos.head.ftqInfo.ftqId + 1.U
  mainRSEnqPort.bits.mainExeBranchInfo.predictJumpAddr := io.queryPcPort.pc
  mainRSEnqPort.bits.mainExeBranchInfo.isBranch        := decoders.head.io.out.info.isBranch
  mainRSEnqPort.bits.mainExeBranchInfo.branchType      := decoders.head.io.out.info.branchType

  rsEnqPorts
    .lazyZip(selectedDecoders)
    .lazyZip(decodeInstInfos)
    .zipWithIndex
    .foreach {
      case (
            (
              rs,
              selectedDecoder,
              decodeInstInfo
            ),
            index
          ) =>
        val robIdReq = io.robIdRequests(index)

        val outReadResults = io.regReadPorts(index)

        rs.bits.regReadResults.lazyZip(outReadResults).lazyZip(selectedDecoder.info.gprReadPorts).foreach {
          case (dst, readRes, decodeRead) =>
            dst.valid := !(decodeRead.en && !readRes.data.valid)
            dst.bits  := readRes.data.bits

            // data dependence
            selectedDecoders.take(index).zipWithIndex.foreach {
              case (prev, prevIdx) =>
                when(decodeRead.en && prev.info.gprWritePort.en && prev.info.gprWritePort.addr === decodeRead.addr) {
                  dst.valid := false.B
                  dst.bits  := io.robIdRequests(prevIdx).result.bits
                }
            }
        }

        val outInstInfo = rs.bits.decodePort.instInfo

        rs.bits.decodePort.decode := selectedDecoder

        outInstInfo := InstInfoNdPort.default

        val isMatched = selectedDecoder.isMatched
        outInstInfo.isValid     := true.B
        outInstInfo.isCsrWrite  := selectedDecoder.info.csrWriteEn
        outInstInfo.exeOp       := selectedDecoder.info.exeOp
        outInstInfo.isTlb       := selectedDecoder.info.isTlb
        outInstInfo.needRefetch := selectedDecoder.info.needRefetch
        outInstInfo.ftqInfo     := decodeInstInfo.ftqInfo

        outInstInfo.forbidParallelCommit := selectedDecoder.info.needRefetch

        outInstInfo.exceptionPos    := ExceptionPos.none
        outInstInfo.exceptionRecord := DontCare
        when(io.hasInterrupt) {
          outInstInfo.exceptionPos    := ExceptionPos.frontend
          outInstInfo.exceptionRecord := Csr.ExceptionIndex.int
        }.elsewhen(decodeInstInfo.exceptionValid) {
          outInstInfo.exceptionPos    := ExceptionPos.frontend
          outInstInfo.exceptionRecord := decodeInstInfo.exception
        }.elsewhen(!isMatched) {
          outInstInfo.exceptionPos    := ExceptionPos.frontend
          outInstInfo.exceptionRecord := Csr.ExceptionIndex.ine
        }.elsewhen(
          io.plv === 3.U &&
            selectedDecoder.info.isPrivilege
        ) {
          outInstInfo.exceptionPos    := ExceptionPos.frontend
          outInstInfo.exceptionRecord := Csr.ExceptionIndex.ipe
        }

        outInstInfo.robId := robIdReq.result.bits

        if (Param.isDiffTest) {
          outInstInfo.pc.get   := decodeInstInfo.pcAddr
          outInstInfo.inst.get := decodeInstInfo.inst
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
      out.bits.exeOp          := rs.bits.decodePort.decode.info.exeOp
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
        pmu.isIssueInst              := out.valid && out.ready && !io.isBackendFlush
        pmu.bubbleFromFrontend       := !rs.io.dequeuePorts.head.valid && !io.isBackendFlush && !isBlockDequeueReg
        pmu.bubbleFromBackend        := out.valid && !out.ready && !io.isBackendFlush
        pmu.bubbleFromDataDependence := rs.io.dequeuePorts.head.valid && !out.valid && !io.isBackendFlush
    }
  }
}
