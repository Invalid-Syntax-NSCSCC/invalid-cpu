package pipeline.simple

import chisel3._
import chisel3.util._
import pipeline.common.enums.RegDataState
import pipeline.simple.bundles.{RegMatchBundle, RegOccupyNdPort, RegReadPort, RegWakeUpNdPort}
import spec._
import utils.MultiMux1

class RegMatchTable(
  issueNum:    Int = Param.issueInstInfoMaxNum,
  pipelineNum: Int = Param.pipelineNum)
    extends Module {

  val io = IO(new Bundle {

    val occupyPorts  = Input(Vec(issueNum, new RegOccupyNdPort))
    val regReadPorts = Vec(issueNum, Vec(Param.regFileReadNum, new RegReadPort))

    val wakeUpPorts = Input(Vec(pipelineNum + 1, new RegWakeUpNdPort))

    val regfileDatas = Input(Vec(Count.reg, UInt(Width.Reg.data)))

    val isFlush = Input(Bool())
  })

  // common match table
  val matchTable           = RegInit(VecInit(Seq.fill(spec.Count.reg)(RegMatchBundle.default)))
  val wbNextMatchTableData = Wire(Vec(Count.reg, Valid(UInt(Width.Reg.data))))
  matchTable.zip(wbNextMatchTableData).foreach {
    case (dst, src) =>
      src.valid := dst.state === RegDataState.ready
      src.bits  := dst.data
      dst.state := Mux(src.valid, RegDataState.ready, RegDataState.busy)
      dst.data  := src.bits
      dst.robId := dst.robId
  }

  io.regReadPorts.foreach { readPorts =>
    readPorts.foreach { r =>
      r.data.valid := wbNextMatchTableData(r.addr).valid
      r.data.bits  := Mux(r.data.valid, wbNextMatchTableData(r.addr).bits, matchTable(r.addr).robId)
    }
  }

  // update match table
  matchTable.zip(wbNextMatchTableData).foreach {
    case (elem, nextElem) =>
      val mux = Module(new MultiMux1(pipelineNum + 1, UInt(spec.Width.Reg.data), zeroWord))
      mux.io.inputs.zip(io.wakeUpPorts).foreach {
        case (input, wakeUpPort) =>
          input.valid := wakeUpPort.en &&
            wakeUpPort.robId === elem.robId
          input.bits := wakeUpPort.data
      }
      when(mux.io.output.valid && elem.state === RegDataState.busy) {
        nextElem.valid := true.B
        nextElem.bits  := mux.io.output.bits
      }
  }

  // occupy port for dispatch
  io.occupyPorts.foreach { occupy =>
    when(occupy.en) {
      matchTable(occupy.addr).state := RegDataState.busy
      matchTable(occupy.addr).robId := occupy.robId
    }
  }

  when(io.isFlush) {
    // Reset registers
    matchTable.zip(io.regfileDatas).foreach {
      case (dst, src) => {
        dst.state := RegDataState.ready
        dst.data  := src
      }
    }
  }
}
