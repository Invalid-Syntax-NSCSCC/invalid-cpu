package pipeline.dispatch

import chisel3._
import chisel3.util._
import control.bundles.CsrReadPort
import pipeline.common.{MultiBaseStageWOSaveIn, MultiQueue}
import pipeline.dispatch.bundles.{ReservationStationBundle, ScoreboardChangeNdPort}
import pipeline.dispatch.enums.ScoreboardState
import pipeline.execution.ExeNdPort
import pipeline.rob.bundles.{InstWbNdPort, RobReadRequestNdPort, RobReadResultNdPort}
import pipeline.rob.enums.RobDistributeSel
import spec.Param.{csrIssuePipelineIndex, loadStoreIssuePipelineIndex}
import spec._
import control.enums.ExceptionPos
import pipeline.common.SimpleMultiBaseStage

// class IssueReqPeerPort(
//   issueNum:    Int = Param.issueInstInfoMaxNum,
//   pipelineNum: Int = Param.pipelineNum)
//     extends Bundle {

//   // `IssueStage` <-> `Rob`
//   val robEmptyNum = Input(UInt(log2Ceil(Param.Width.Rob._length + 1).W))
//   val requests    = Output(Vec(issueNum, new RobReadRequestNdPort))

//   // `Cu` -> `IssueStage`
//   val branchFlush = Input(Bool())

//   val plv = Input(UInt(2.W))
// }

class RenamePeerPort(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Bundle {

  // `IssueStage` <-> `Rob`
  val robEmptyNum = Input(UInt(log2Ceil(Param.Width.Rob._length + 1).W))
  val requests    = Output(Vec(issueNum, new RobReadRequestNdPort))
  val results     = Input(Vec(issueNum, new RobReadResultNdPort))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(pipelineNum, new InstWbNdPort))

  // `IssueStage` <-> `Scoreboard(csr)`
  // val csrOccupyPort = Output(new ScoreboardChangeNdPort)
  // val csrcore       = Input(ScoreboardState())
  // val csrReadPort = Flipped(new CsrReadPort)

  // `Cu` -> `IssueStage`
  val branchFlush = Input(Bool())

  val plv = Input(UInt(2.W))
}

class RenameStage(
  issueNum:          Int = Param.issueInstInfoMaxNum,
  pipelineNum:       Int = Param.pipelineNum,
  reservationLength: Int = Param.reservationStationDeep)
//   extends Module {
// val io = IO(new Bundle {
//   val ins     = Vec(issueNum, Flipped(Decoupled(new FetchInstDecodeNdPort)))
//   val outs    = Vec(issueNum, Decoupled(new ExeNdPort))
//   val peer    = new IssueStagePeerPort
//   val isFlush = Input(Bool())
// })
    extends SimpleMultiBaseStage(
      new FetchInstDecodeNdPort,
      new DispatchNdPort,
      FetchInstDecodeNdPort.default,
      Some(new RenamePeerPort),
      issueNum,
      issueNum
    ) {

  val peer = io.peer.get

  // Fallback
  peer.requests.foreach { req =>
    req.en := false.B
    req.readRequests.foreach { port =>
      port.en   := false.B
      port.addr := DontCare
    }
    req.writeRequest.en   := false.B
    req.writeRequest.addr := DontCare
  }

  val reservationStation = Module(
    new MultiQueue(
      reservationLength,
      issueNum,
      issueNum,
      new ReservationStationBundle,
      ReservationStationBundle.default,
      writeFirst = false
    )
  )
  // fall back
  reservationStation.io.enqueuePorts.foreach { port =>
    port.valid := true.B
    port.bits  := DontCare
  }
  reservationStation.io.dequeuePorts.foreach(_.ready := false.B)
  reservationStation.io.setPorts.zip(reservationStation.io.elems).foreach {
    case (dst, src) =>
      dst.valid := false.B
      dst.bits  := src
  }
  reservationStation.io.isFlush := io.isFlush

  // stop fetch when branch
  val fetchEnableFlag = RegInit(true.B)

  // valid
  val requestValids = io.ins
    .lazyZip(
      reservationStation.io.enqueuePorts
    )
    .zipWithIndex
    .map {
      case ((in, rs), idx) =>
        in.valid && rs.ready && idx.U < peer.robEmptyNum && fetchEnableFlag
    }

  io.ins.zip(requestValids).foreach {
    case (in, rValid) =>
      in.ready := rValid
  }
  reservationStation.io.enqueuePorts.zip(requestValids).foreach {
    case (rs, rValid) =>
      rs.valid := rValid
  }
  peer.requests.zip(requestValids).foreach {
    case (req, rValid) =>
      req.en := rValid
  }

  // request
  peer.requests.zip(selectedIns).foreach {
    case (req, in) =>
      val decode = in.decode
      req.writeRequest := decode.info.gprWritePort
      req.readRequests.zip(decode.info.gprReadPorts).foreach {
        case (dst, src) =>
          dst := src
      }
  }

  // -> reservation station
  selectedIns
    .lazyZip(peer.results)
    .lazyZip(reservationStation.io.enqueuePorts)
    .foreach {
      case (in, result, rs) =>
        rs.bits.regReadPort.preExeInstInfo := in.decode.info
        rs.bits.regReadPort.instInfo       := in.instInfo

        rs.bits.robResult := result
        peer.writebacks.foreach { wb =>
          in.decode.info.gprReadPorts
            .lazyZip(result.readResults)
            .lazyZip(rs.bits.robResult.readResults)
            .foreach {
              case (readPort, robReadResult, dst) =>
                when(
                  wb.en && readPort.en &&
                    robReadResult.sel === RobDistributeSel.robId &&
                    wb.robId === robReadResult.result
                ) {
                  dst.sel    := RobDistributeSel.realData
                  dst.result := wb.data
                }
            }
        }

        // imm
        when(in.decode.info.isHasImm) {
          rs.bits.robResult.readResults(1).sel    := RobDistributeSel.realData
          rs.bits.robResult.readResults(1).result := in.decode.info.imm
        }
    }

  // commit fill reservation station
  reservationStation.io.elems
    .lazyZip(reservationStation.io.elemValids)
    .lazyZip(reservationStation.io.setPorts)
    .foreach {
      case (elem, elemValid, set) =>
        when(elemValid) {
          io.peer.get.writebacks.foreach { wb =>
            elem.robResult.readResults.zip(set.bits.robResult.readResults).foreach {
              case (readResult, setReadResult) =>
                when(readResult.sel === RobDistributeSel.robId && wb.en && readResult.result === wb.robId) {
                  set.valid            := true.B
                  setReadResult.sel    := RobDistributeSel.realData
                  setReadResult.result := wb.data
                }
            }
          }
        }
    }

  val deqEns = Wire(Vec(issueNum, Bool()))

  // out
  reservationStation.io.dequeuePorts.lazyZip(resultOuts).zipWithIndex.foreach {
    case ((rs, out), idx) =>
      val deqEn = out.ready && rs.valid &&
        rs.bits.robResult.readResults.forall(
          _.sel === RobDistributeSel.realData
        ) && deqEns.take(idx).foldLeft(true.B)(_ && _)
      deqEns(idx) := deqEn
      out.valid   := deqEn
      rs.ready    := deqEn

      out.bits.exePort.leftOperand  := rs.bits.robResult.readResults(0).result
      out.bits.exePort.rightOperand := rs.bits.robResult.readResults(1).result
      out.bits.exePort.exeSel       := rs.bits.regReadPort.preExeInstInfo.exeSel
      out.bits.exePort.exeOp        := rs.bits.regReadPort.preExeInstInfo.exeOp

      out.bits.exePort.gprWritePort := rs.bits.regReadPort.preExeInstInfo.gprWritePort
      // jumbBranch / memLoadStort / csr
      out.bits.exePort.jumpBranchAddr := rs.bits.regReadPort.preExeInstInfo.jumpBranchAddr

      // TODO: Read Csr Data in issue
      out.bits.csrReadEn := rs.bits.regReadPort.preExeInstInfo.csrReadEn

      out.bits.exePort.instInfo       := rs.bits.regReadPort.instInfo
      out.bits.exePort.instInfo.robId := rs.bits.robResult.robId

      when(
        peer.plv =/= 0.U &&
          rs.bits.regReadPort.preExeInstInfo.isPrivilege &&
          rs.bits.regReadPort.instInfo.exceptionPos =/= ExceptionPos.none
      ) {
        out.bits.exePort.instInfo.exceptionPos    := ExceptionPos.backend
        out.bits.exePort.instInfo.exceptionRecord := Csr.ExceptionIndex.ipe
      }

      out.bits.issueEns.zip(rs.bits.regReadPort.preExeInstInfo.issueEn).foreach {
        case (dst, src) =>
          dst := src
      }

  }

  when(peer.branchFlush) {
    // peer.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
    fetchEnableFlag := false.B
  }

  when(io.isFlush) {
    // peer.requests.foreach(_.en := false.B)
    io.ins.foreach(_.ready := false.B)
    fetchEnableFlag := true.B
  }

}
