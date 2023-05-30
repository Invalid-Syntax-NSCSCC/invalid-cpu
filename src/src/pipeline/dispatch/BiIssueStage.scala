package pipeline.dispatch

import chisel3._
import chisel3.util._
import spec._
import pipeline.dispatch.enums.{IssueStageState => State}
import pipeline.writeback.bundles.InstInfoNdPort
import Csr.ExceptionIndex
import common.bundles.PassThroughPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dataforward.bundles.ReadPortWithValid
import pipeline.queue.bundles.DecodeOutNdPort
import pipeline.rob.bundles.RobIdDistributePort
import pipeline.dispatch.bundles.RegReadPortWithValidBundle
import pipeline.dispatch.enums.ScoreboardState
import pipeline.common.MultiBaseStage
import chisel3.experimental.BundleLiterals._

class FetchInstDecodeNdPort extends Bundle {
  val decode   = new DecodeOutNdPort
  val instInfo = new InstInfoNdPort
}

object FetchInstDecodeNdPort {
  val default = (new FetchInstDecodeNdPort).Lit(
    _.decode -> DecodeOutNdPort.default,
    _.instInfo -> InstInfoNdPort.default
  )
}

class BiIssueStagePeerPort(
  issueNum:       Int = 2,
  scoreChangeNum: Int = Param.regFileWriteNum,
  robIdLength:    Int = 32,
  robLengthLog:   Int = 4
  ) extends Bundle{
    // `IssueStage` <-> `RobStage`
    val robEmptyNum = Input(UInt(robLengthLog.W))
    val idGetPorts  = Vec(issueNum, Flipped(new RobIdDistributePort(idLength = robIdLength)))

    // `IssueStage` <-> `Scoreboard`
    val occupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val regScores    = Input(Vec(Count.reg, ScoreboardState()))

    // `IssueStage` <-> `Scoreboard(csr)`
    val csrOccupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val csrRegScores    = Input(Vec(Count.csrReg, ScoreboardState()))
}

// TODO: deal WAR data hazard
class BiIssueStage(
  issueNum:       Int = 2,
  scoreChangeNum: Int = Param.regFileWriteNum,
  robIdLength:    Int = 32,
  robLengthLog:   Int = 4)
    extends MultiBaseStage(
      new FetchInstDecodeNdPort, 
      new RegReadNdPort,
      FetchInstDecodeNdPort.default,
      Some(new BiIssueStagePeerPort(issueNum, scoreChangeNum, robIdLength, robLengthLog)),
      spec.Param.issueInstInfoMaxNum,
      spec.Param.issueInstInfoMaxNum
    ) {

  // fall back
  resultOutsReg.foreach(_.bits := 0.U.asTypeOf(resultOutsReg(0).bits))
  io.peer.get.csrOccupyPortss.foreach(_(0) := ScoreboardChangeNdPort.default)
  io.peer.get.occupyPortss.foreach(_(0) := ScoreboardChangeNdPort.default)
  io.peer.get.idGetPorts.foreach(_.writeEn := false.B)


  val canIssueMaxNumFromPipeline = WireDefault(validToOuts.map(_.asUInt).reduce(_ +& _))
  val canIssueMaxNumFromRob = WireDefault(io.peer.get.robEmptyNum)
  // val canIssueMaxNumFromRob      = WireDefault(io.peer.get.robEmptyNum +& selectedIns.map{
  //   in =>
  //     (!in.decode.info.gprWritePort.en).asUInt
  // }.reduce(_ +& _) )

  val canIssueMaxNum = Mux(
    canIssueMaxNumFromRob < canIssueMaxNumFromPipeline,
    canIssueMaxNumFromRob,
    canIssueMaxNumFromPipeline
  )

  val fetchCanIssue = WireDefault(VecInit(Seq.fill(issueNum)(true.B)))
  isComputeds.zip(fetchCanIssue).foreach{
    case (isComputed, fetchValid) =>
      isComputed := fetchValid
  }

  fetchCanIssue.lazyZip(selectedIns).zipWithIndex.foreach{
    case ((fetchValid, in), idx) =>
      fetchValid := in.instInfo.isValid &&
        !(
          in.decode.info.gprReadPorts.map{
            readPort =>
              readPort.en && (io.peer.get.regScores(readPort.addr) =/= ScoreboardState.free)
          }.reduce(_ || _)
        ) && 
        !(
          in.instInfo.csrWritePort.en && 
          (io.peer.get.csrRegScores(in.instInfo.csrWritePort.addr) =/= ScoreboardState.free)
        ) &&
        !selectedIns.slice(0,idx).map{
          prevIn =>
            prevIn.decode.info.gprReadPorts.map{
              prevInOneRead =>
                in.decode.info.gprReadPorts.map{
                  inOneRead =>
                    prevInOneRead.en && inOneRead.en && (prevInOneRead.addr === inOneRead.addr) && (prevInOneRead.addr =/= 0.U)
                }.reduce(_ || _)
            }.reduce(_ || _)
        }.foldLeft(false.B)(_ || _)
  }

  val fetchInfos = Wire(Vec(issueNum, ValidIO(new RegReadNdPort)))
  fetchInfos.zip(selectedIns).foreach{
    case (dst, src) =>
      dst.valid := src.instInfo.isValid
      dst.bits.instInfo := src.instInfo
      dst.bits.preExeInstInfo := src.decode.info
  }

  require(issueNum == 2)

  io.ins.lazyZip(isLastComputeds).zipWithIndex.foreach{
    case ((in, isLastComputed), index) =>
      in.ready := isLastComputed && index.U < canIssueMaxNum
  }

  def connect(src_idx: Int, dst_idx: Int, occupy_port_idx: Int): Unit = {
    resultOutsReg(dst_idx).valid := fetchInfos(src_idx).bits.instInfo.isValid
    resultOutsReg(dst_idx) := fetchInfos(src_idx)
    isComputeds(src_idx) := true.B

    io.peer.get.occupyPortss(occupy_port_idx)(0) := fetchInfos(src_idx).bits.preExeInstInfo.gprWritePort
    io.peer.get.csrOccupyPortss(occupy_port_idx)(0).en := fetchInfos(src_idx).bits.instInfo.csrWritePort.en
    io.peer.get.csrOccupyPortss(occupy_port_idx)(0).addr := fetchInfos(src_idx).bits.instInfo.csrWritePort.addr

    io.peer.get.idGetPorts(occupy_port_idx).writeEn := fetchInfos(src_idx).bits.preExeInstInfo.gprWritePort.en
    resultOutsReg(dst_idx).bits.instInfo.robId := io.peer.get.idGetPorts(occupy_port_idx).id
  }

  if (issueNum == 2) {
      when (fetchCanIssue(0) && canIssueMaxNum >= 1.U) {
        when(validToOuts(0)) {
          // 0 --> 0
          connect(0, 1, 0)

          when(validToOuts(1) && fetchCanIssue(1) && canIssueMaxNum >= 2.U) {
            // 1 --> 1
            connect(1, 1, 1)
          }
        }.elsewhen(validToOuts(1)) {
          // 0 --> 1
            connect(1, 0, 0)
        }
      }
  }
}
