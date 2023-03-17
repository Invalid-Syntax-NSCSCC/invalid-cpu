package pipelie.bundles

import chisel3._
import chisel3.util._

class PipelineControlNDPort extends Bundle {
    val flush = Bool()
    val clear = Bool()
    val advance = Bool()
}