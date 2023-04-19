package pipeline.dispatch

import chisel3._
import chisel3.util._
import spec._
import control.bundles.PipelineControlNDPort
import pipeline.dispatch.enums.{IssueStageState => State}
import pipeline.writeback.bundles.InstInfoNdPort
import CsrRegs.ExceptionIndex
import common.bundles.PassThroughPort
import pipeline.dispatch.bundles.DecodeOutNdPort
import pipeline.dispatch.bundles.ScoreboardChangeNdPort
import pipeline.dispatch.bundles.IssuedInfoNdPort
import pipeline.dataforward.bundles.ReadPortWithValid
import pipeline.dispatch.bundles.IssueInfoWithValidBundle

class BiIssueStage(
  issueNum:       Int = 2,
  scoreChangeNum: Int = Param.regFileWriteNum)
    extends Module {
  val io = IO(new Bundle {
    // `InstQueue` -> `IssueStage`
    val fetchInstDecodePorts = Vec(
      issueNum,
      Flipped(Decoupled(new Bundle {
        val decode   = new DecodeOutNdPort
        val instInfo = new InstInfoNdPort
      }))
    )

    // `IssueStage` <-> `RobStage`
    val idGetPorts = Vec(issueNum, Flipped(new ReadPortWithValid))

    // `IssueStage` <-> `Scoreboard`
    val occupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val regScores    = Input(Vec(Count.reg, Bool()))

    // `IssueStage` <-> `Scoreboard(csr)`
    val csrOccupyPortss = Vec(issueNum, Output(Vec(scoreChangeNum, new ScoreboardChangeNdPort)))
    val csrRegScores    = Input(Vec(Count.csrReg, Bool()))

    // `IssueStage` -> `RegReadStage` (next clock pulse)
    val issuedInfoPorts = Vec(issueNum, Output(new IssuedInfoNdPort))
    val instInfoPorts   = Vec(issueNum, Output(new InstInfoNdPort))
    // val instInfoPassThroughPort = new PassThroughPort(new InstInfoNdPort)

    // pipeline control signal
    // `Cu` -> `IssueStage`
    val pipelineControlPorts = Vec(issueNum, Input(new PipelineControlNDPort))
  })

  // val instInfosReg = Reg(Vec(issueNum, new InstInfoNdPort))
  // instInfosReg.foreach(InstInfoNdPort.setDefault(_))
  val instInfosReg = RegInit(VecInit(Seq.fill(issueNum)(InstInfoNdPort.default)))
  io.instInfoPorts := instInfosReg

  val issueInfosReg = RegInit(VecInit(Seq.fill(issueNum)(IssuedInfoNdPort.default)))
  io.issuedInfoPorts := issueInfosReg

  // fall back
  if (true) {
    io.fetchInstDecodePorts.foreach { port =>
      port.ready := false.B
    }
    io.occupyPortss.foreach(_.foreach { port =>
      port.en   := false.B
      port.addr := zeroWord
    })
    io.csrOccupyPortss.foreach(_.foreach { port =>
      port.en   := false.B
      port.addr := zeroWord
    })
    io.issuedInfoPorts.foreach(_ := IssuedInfoNdPort.default)
    io.instInfoPorts.foreach(_ := InstInfoNdPort.default)
  }

  // val nextStates = WireDefault(VecInit(Seq.fill(issueNum)(State.nonBlocking)))
  // val statesReg  = RegInit(VecInit(Seq.fill(issueNum)(State.nonBlocking)))

  /** Combine stage 1 : get fetch infos
    */

  val fetchInfos         = WireDefault(VecInit(Seq.fill(issueNum)(IssueInfoWithValidBundle.default)))
  val fetchInfosStoreReg = RegInit(VecInit(Seq.fill(issueNum)(IssueInfoWithValidBundle.default)))

  fetchInfos
    .lazyZip(io.fetchInstDecodePorts)
    .foreach((dst, src) => {
      dst.valid     := src.valid
      dst.instInfo  := src.bits.instInfo
      dst.issueInfo := src.bits.instInfo
    })

  /** Combine stage 2 : Select to issue ; decide input
    */

  // 优先valid第0个
  val selectValidWires = Wire(
    Vec(
      issueNum,
      new IssueInfoWithValidBundle
    )
  )
  selectValidWires.foreach(_ := 0.U.asTypeOf(selectValidWires(0)))

  val canIssueMaxNum = io.pipelineControlPorts.map(_.stall.asUInt).reduce(_ +& _)

  val fetchStoreRegCanIssue = WireDefault(VecInit(fetchInfosStoreReg.map { fetchInfos =>
    fetchInfos.valid &&
    !((fetchInfos.issueInfo.info.gprReadPorts
      .map({ readPort =>
        readPort.en && io.regScores(readPort.addr)
      }))
      .reduce(_ || _)) &&
    !(fetchInfos.issueInfo.info.csrReadEn && io.csrRegScores(fetchInfos.issueInfo.info.csrAddr))
  }))

  val fetchCanIssue = WireDefault(VecInit(fetchInfos.map { fetchInfos =>
    fetchInfos.valid &&
    !((fetchInfos.issueInfo.info.gprReadPorts
      .map({ readPort =>
        readPort.en && io.regScores(readPort.addr)
      }))
      .reduce(_ || _)) &&
    !(fetchInfos.issueInfo.info.csrReadEn && io.csrRegScores(fetchInfos.issueInfo.info.csrAddr))
  }))

  when(canIssueMaxNum === 1.U) {
    // issue 1 inst
    // check : store reg 0 -> fetch 0
    when(fetchInfosStoreReg(0).valid) {
      // store reg 0 valid
      when(fetchStoreRegCanIssue(0)) {
        selectValidWires(0) := fetchInfosStoreReg(0)
        when(fetchInfosStoreReg(1).valid) {
          fetchInfosStoreReg(0)            := fetchInfosStoreReg(1)
          fetchInfosStoreReg(1)            := fetchInfos(0)
          io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid
        }.otherwise {
          fetchInfosStoreReg(0) := fetchInfos(0)
          fetchInfosStoreReg(1) := fetchInfos(1)
          // assumption : it do not exist fetch 1 valid but fetch 0 not valid
          io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid
          io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid
        }
      }.elsewhen(!fetchInfosStoreReg(1).valid) {
        fetchInfosStoreReg(1)            := fetchInfos(0)
        io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid
      }

    }.elsewhen(fetchInfos(0).valid) {
      // fetch 0 valid && store reg no valid : issue fetch 0
      when(fetchCanIssue(0)) {
        selectValidWires(0)              := fetchInfos(0)
        io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid // true.B
        fetchInfosStoreReg(0)            := fetchInfos(1)
        io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid
      }.otherwise {
        fetchInfosStoreReg(0)            := fetchInfos(0)
        fetchInfosStoreReg(1)            := fetchInfos(1)
        io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid
        io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid
      }
    }
  }.elsewhen(canIssueMaxNum === 2.U) {
    // issue 2 inst
    // check : store reg 0 [ -> store reg 1] -> fetch 0 [ -> fetch 1 ]

    // get first issue
    when(fetchInfosStoreReg(0).valid) {
      when(fetchStoreRegCanIssue(0)) {
        selectValidWires(0) := fetchInfosStoreReg(0)
        // get second issue
        when(fetchInfosStoreReg(1).valid) {
          when(
            fetchStoreRegCanIssue(1) &&
              !(
                fetchInfosStoreReg(0).issueInfo.info.gprWritePort.en &&
                  fetchInfosStoreReg(1).issueInfo.info.gprReadPorts.map { readPort =>
                    readPort.en && (readPort.addr === fetchInfosStoreReg(0).issueInfo.info.gprWritePort.addr)
                  }.reduce(_ || _)
              )
          ) {
            // issue store reg 0, 1
            selectValidWires(1)              := fetchInfosStoreReg(1)
            fetchInfosStoreReg(0)            := fetchInfos(0)
            fetchInfosStoreReg(1)            := fetchInfos(1)
            io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid
            io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid
          }.otherwise { // store reg 1 valid but no issue
            // only issue store reg 0
            fetchInfosStoreReg(0)            := fetchInfosStoreReg(1)
            fetchInfosStoreReg(1)            := fetchInfos(0)
            io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid
          }
        }.otherwise { // store reg 1 not valid
          when(fetchInfos(0).valid) {
            when(
              fetchCanIssue(0) &&
                !(
                  fetchInfosStoreReg(0).issueInfo.info.gprWritePort.en &&
                    fetchInfos(0).issueInfo.info.gprReadPorts.map { readPort =>
                      readPort.en && (readPort.addr === fetchInfosStoreReg(0).issueInfo.info.gprWritePort.addr)
                    }.reduce(_ || _)
                )
            ) {
              // issue store reg 0, fetch 0
              selectValidWires(1)              := fetchInfos(0)
              io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid // true.B
              fetchInfosStoreReg(0)            := fetchInfos(1)
              io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid
            }.otherwise { // fetch 0 valid but no issue
              // only issue store reg 0
              fetchInfosStoreReg(0)            := fetchInfos(0)
              io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid // true.B
            }
          }
        }
      }.otherwise { // store reg 0 valid but no issue
        when(!fetchInfosStoreReg(1).valid) {
          fetchInfosStoreReg(1)            := fetchInfos(0)
          io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid
        }
      }
    }.otherwise { // store reg 0, 1 not valid
      when(fetchInfos(0).valid) {
        when(fetchCanIssue(0)) {
          // issue fetch 0
          selectValidWires(0)              := fetchInfos(0)
          io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid // true.B
          when(fetchInfos(1).valid) {
            when(
              fetchCanIssue(1) &&
                !(
                  fetchInfos(0).issueInfo.info.gprWritePort.en &&
                    fetchInfos(1).issueInfo.info.gprReadPorts.map { readPort =>
                      readPort.en && (readPort.addr === fetchInfos(0).issueInfo.info.gprWritePort.addr)
                    }.reduce(_ || _)
                )
            ) {
              // issue fetch 0, 1
              selectValidWires(1)              := fetchInfos(1)
              io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid // true.B
            }.otherwise { // fetch 1 valid but no issue
              fetchInfosStoreReg(0)            := fetchInfos(1)
              io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid
            }
          } // fetch 1 not valid
        }.otherwise { // fetch 0 valid but no issue
          fetchInfosStoreReg(0)            := fetchInfos(0)
          fetchInfosStoreReg(1)            := fetchInfos(1)
          io.fetchInstDecodePorts(0).ready := io.fetchInstDecodePorts(0).valid // true.B
          io.fetchInstDecodePorts(1).ready := io.fetchInstDecodePorts(1).valid
        }
      } // fetch 0 not valid
    }
  }

  /** Combine stage 3 : valid inst --> issue ; connect score board
    */
  // default : no issue
  when(selectValidWires(0).valid) {
    when(!io.pipelineControlPorts(0).stall) {
      // select 0 -> issue 0
      instInfosReg(0)       := selectValidWires(0).instInfo
      issueInfosReg(0)      := selectValidWires(0).issueInfo
      io.occupyPortss(0)(0) := selectValidWires(0).issueInfo.info.gprWritePort

      when(selectValidWires(1).valid && !io.pipelineControlPorts(1).stall) {
        // select 1 -> issue 1
        instInfosReg(1)       := selectValidWires(1).instInfo
        issueInfosReg(1)      := selectValidWires(1).issueInfo
        io.occupyPortss(1)(0) := selectValidWires(1).issueInfo.info.gprWritePort
      }
    }.elsewhen(!io.pipelineControlPorts(1).stall) {
      // select 0 -> issue 1
      instInfosReg(1)       := selectValidWires(0).instInfo
      issueInfosReg(1)      := selectValidWires(0).issueInfo
      io.occupyPortss(0)(0) := selectValidWires(0).issueInfo.info.gprWritePort
    }
  }

  // clear
  when(io.pipelineControlPorts.map(_.clear).reduce(_ || _)) {
    instInfosReg.foreach(_ := InstInfoNdPort.default)
    issueInfosReg.foreach(_ := IssuedInfoNdPort.default)
  }
  // flush all regs
  when(io.pipelineControlPorts.map(_.flush).reduce(_ || _)) {
    instInfosReg.foreach(_ := InstInfoNdPort.default)
    issueInfosReg.foreach(_ := IssuedInfoNdPort.default)
    fetchInfosStoreReg.foreach(_ := IssueInfoWithValidBundle.default)
    // statesReg.foreach(_ := State.nonBlocking)
  }

}
