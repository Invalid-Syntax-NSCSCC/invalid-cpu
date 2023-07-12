package pipeline.dispatch

import chisel3._
import chisel3.util._
import control.bundles.CsrReadPort
import pipeline.common.{MultiBaseStageWOSaveIn, MultiQueue}
import pipeline.dispatch.bundles._
import pipeline.commit.bundles._
import pipeline.dispatch.enums.ScoreboardState
import pipeline.execution.ExeNdPort
import pipeline.rob.bundles.{InstWbNdPort, RobReadRequestNdPort, RobReadResultNdPort}
import pipeline.rob.enums.RobDistributeSel
import spec.Param.{csrIssuePipelineIndex, loadStoreIssuePipelineIndex}
import spec._
import control.enums.ExceptionPos
import pipeline.common.SimpleMultiBaseStage
import pipeline.dispatch.rs._
import pipeline.dispatch._
import pipeline.queue._
import chisel3.experimental.BundleLiterals._

// class RegReadNdPort extends Bundle {
//   val preExeInstInfo = new PreExeInstNdPort
//   val instInfo       = new InstInfoNdPort
// }

// object RegReadNdPort {
//   def default = (new RegReadNdPort).Lit(
//     _.preExeInstInfo -> PreExeInstNdPort.default,
//     _.instInfo -> InstInfoNdPort.default
//   )
// }

class NewRenamePeerPort(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Bundle {

  // `IssueStage` <-> `Scoreboard(csr)`
  val csrOccupyPort = Output(new ScoreboardChangeNdPort)
  val csrScore      = Input(ScoreboardState())

  // `IssueStage` <-> `Rob`
  val requests = Vec(issueNum, Decoupled(new RobReadRequestNdPort))
  val results  = Input(Vec(issueNum, new RobReadResultNdPort))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(pipelineNum, new InstWbNdPort))

  // `Cu` -> `IssueStage`
  val branchFlush = Input(Bool())
}

class NewRenameStage(
  issueNum:          Int = Param.issueInstInfoMaxNum,
  pipelineNum:       Int = Param.pipelineNum,
  reservationLength: Int = Param.Width.ReservationStation._length)
    extends Module {
  val io = IO(new Bundle {
    val ins     = Vec(issueNum, Flipped(Decoupled(new FetchInstDecodeNdPort)))
    val outs    = Vec(issueNum, Decoupled(new ReservationStationBundle))
    val peer    = Some(new NewRenamePeerPort)
    val isFlush = Input(Bool())
  })
  protected val selectedIns: Vec[FetchInstDecodeNdPort] = Wire(
    Vec(issueNum, new FetchInstDecodeNdPort)
  )
  selectedIns.lazyZip(io.ins).foreach {
    case (selectIn, in) => {
      selectIn := Mux(
        in.valid,
        in.bits,
        FetchInstDecodeNdPort.default
      )
    }
  }

  val peer = io.peer.get

  // Fallback
  peer.requests.foreach { req =>
    req.valid := false.B
  }

  val reservationStation = Module(
    new InOrderReservationStation(
      queueLength          = 4,
      enqMaxNum            = issueNum,
      deqMaxNum            = issueNum,
      channelNum           = 2,
      channelLength        = 2,
      supportCheckForIssue = false
    )
  )

  reservationStation.io.writebacks.zip(io.peer.get.writebacks).foreach {
    case (dst, src) =>
      dst <> src
  }
  reservationStation.io.isFlush := io.isFlush

  // stop fetch when branch
  val fetchEnableFlag = RegInit(true.B)

  // valid
  val requestValids = io.ins.zip(reservationStation.io.enqueuePorts).zip(io.peer.get.requests).map {
    case ((in, rs), req) =>
      in.valid && rs.ready && req.ready && fetchEnableFlag
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
      req.valid := rValid
  }

  // request
  peer.requests.zip(selectedIns).foreach {
    case (req, in) =>
      val decode = in.decode
      req.bits.writeRequest := decode.info.gprWritePort
      req.bits.readRequests.zip(decode.info.gprReadPorts).foreach {
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
        rs.bits.robResult                  := result

        // imm
        when(in.decode.info.isHasImm) {
          rs.bits.robResult.readResults(1).sel    := RobDistributeSel.realData
          rs.bits.robResult.readResults(1).result := in.decode.info.imm
        }
    }

  val isCsr = WireDefault(
    VecInit(
      reservationStation.io.dequeuePorts.map(_.bits.regReadPort.preExeInstInfo.needCsr)
    )
  )
  peer.csrOccupyPort.en := isCsr(0) && io.outs(0).ready && io.outs(0).valid
  // peer.csr
  reservationStation.io.dequeuePorts.zip(io.outs).zipWithIndex.foreach {
    case ((src, dst), idx) =>
      dst.valid := src.valid
      dst.bits  := src.bits
      src.ready := dst.ready
      if (idx == 0) {
        when(isCsr(idx) && peer.csrScore =/= ScoreboardState.free) {
          dst.valid := false.B
          src.ready := false.B
        }
      } else {
        when(isCsr(idx) || !reservationStation.io.dequeuePorts(idx - 1).ready) {
          dst.valid := false.B
          src.ready := false.B
        }
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
