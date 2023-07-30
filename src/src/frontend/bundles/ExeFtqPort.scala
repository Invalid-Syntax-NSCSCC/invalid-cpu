package frontend.bundles
import chisel3._
import chisel3.util._
import spec.Param
class ExeFtqPort extends Bundle {
  val queryPcBundle = new QueryPcBundle
  val commitBundle  = Input(new ExeCommitFtqNdPort)
}

class QueryPcBundle extends Bundle {
  val ftqId = Input(UInt(spec.Param.BPU.ftqPtrWidth.W))
  val pc    = Output(UInt(spec.Width.Mem.addr))
}

class ExeCommitFtqNdPort extends Bundle {
  val ftqMetaUpdateValid       = Bool()
  val ftqMetaUpdateFtbDirty    = Bool()
  val ftqMetaUpdateJumpTarget  = UInt(spec.Width.Mem.addr)
  val ftqMetaUpdateFallThrough = UInt(spec.Width.Mem.addr)
  val ftqUpdateMetaId          = UInt(Param.BPU.Width.id)
}
