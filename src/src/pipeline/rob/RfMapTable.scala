package pipeline.rob

import chisel3._
import chisel3.util._
import common.bundles.RfReadPort
import spec.Param

class RfMapTable(
  regReadNum: Int = Param.regFileReadNum,
  issueNum: Int = 2
) extends Module {
  val io = IO(new Bundle {
    val readRequestPort = new RfReadPort
    
    val readRegfilePort = Vec(regReadNum, Flipped(new RfReadPort))
    
  })
  require(issueNum == 2)
}