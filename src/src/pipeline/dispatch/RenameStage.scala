package pipeline.dispatch

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import control.enums.ExceptionPos
import pipeline.commit.bundles._
import pipeline.common.SimpleMultiBaseStage
import pipeline.dispatch.bundles._
import pipeline.dispatch.rs._
import pipeline.queue._
import pipeline.rob.bundles.{InstWbNdPort, RobReadRequestNdPort, RobReadResultNdPort}
import pipeline.rob.enums.RobDistributeSel
import spec._

class RegReadNdPort extends Bundle {
  val preExeInstInfo = new PreExeInstNdPort
  val instInfo       = new InstInfoNdPort
}

object RegReadNdPort {
  def default = (new RegReadNdPort).Lit(
    _.preExeInstInfo -> PreExeInstNdPort.default,
    _.instInfo -> InstInfoNdPort.default
  )
}

class RenamePeerPort(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Bundle {

  // `IssueStage` <-> `Rob`
  val requests = Vec(issueNum, Decoupled(new RobReadRequestNdPort))
  val results  = Input(Vec(issueNum, new RobReadResultNdPort))

  // `LSU / ALU` -> `IssueStage
  val writebacks = Input(Vec(pipelineNum, new InstWbNdPort))

  // `Cu` -> `IssueStage`
  val branchFlush = Input(Bool())

  val plv = Input(UInt(2.W))
}

class RenameStage(
  issueNum:          Int = Param.issueInstInfoMaxNum,
  pipelineNum:       Int = Param.pipelineNum,
  reservationLength: Int = Param.Width.ReservationStation._length)
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
    req.valid := false.B
    req.bits.readRequests.foreach { port =>
      port.en   := false.B
      port.addr := DontCare
    }
    req.bits.writeRequest.en   := false.B
    req.bits.writeRequest.addr := DontCare
  }

  val reservationStation: BaseReservationStation = Module(
    if (Param.isOutOfOrderIssue) {
      new OutOfOrderReservationStation(
        reservationLength,
        issueNum,
        issueNum,
        Param.Width.ReservationStation._channelNum,
        Param.Width.ReservationStation._channelLength
      )
    } else {
      new InOrderReservationStation(
        reservationLength,
        issueNum,
        issueNum,
        Param.Width.ReservationStation._channelNum,
        Param.Width.ReservationStation._channelLength
      )
    }
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

  val deqEns = Wire(Vec(issueNum, Bool()))

  // out
  reservationStation.io.dequeuePorts.lazyZip(resultOuts).zipWithIndex.foreach {
    case ((rs, out), idx) =>
      val deqEn = out.ready && rs.valid && deqEns.take(idx).foldLeft(true.B)(_ && _)
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

      // Read Csr Data in exe

      out.bits.exePort.instInfo       := rs.bits.regReadPort.instInfo
      out.bits.exePort.instInfo.robId := rs.bits.robResult.robId

      when(
        peer.plv === 0.U &&
          rs.bits.regReadPort.preExeInstInfo.isPrivilege &&
          rs.bits.regReadPort.instInfo.exceptionPos === ExceptionPos.none
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
