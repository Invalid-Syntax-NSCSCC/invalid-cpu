package pipeline.dataforward

import chisel3._
import common.bundles.RfWriteNdPort
import pipeline.dataforward.bundles.ReadPortWithValid
import spec._
class DataForwardStage(
  dataForwardNum: Int = Param.dataForwardInputNum,
  readNum:        Int = Param.regFileReadNum)
    extends Module {
  val io = IO(new Bundle {
    val writePorts = Input(Vec(dataForwardNum, new RfWriteNdPort))
    val readPorts  = Vec(readNum, new ReadPortWithValid)
  })

  io.readPorts.foreach { readPort =>
    readPort.valid := false.B
    readPort.data  := zeroWord
    when(readPort.en) {
      io.writePorts.reverse.foreach { writePort =>
        when(writePort.en && (readPort.addr === writePort.addr)) {
          readPort.data  := writePort.data
          readPort.valid := true.B
        }
      }
    }
  }
}
