package pipeline.queue.decode

import chisel3._
import pipeline.queue.bundles.DecodePort
import spec._

object DispatchType extends Enumeration {
  type Type = Value
  val common, loadStore, csrOrBranch = Value
}

abstract class Decoder extends Module {
  // A decoder should:
  // 1) Extract and extend immediate from instruction, if it has
  // 2) Extract register information from instruction
  // 3) Something else...

  val io = IO(new DecodePort)

  io.out.info.issueEn.zipWithIndex.foreach {
    case (en, idx) =>
      en := false.B
  }

  def selectIssueEn(dispatchType: DispatchType.Type): Unit = {
    dispatchType match {
      case DispatchType.common => {
        io.out.info.issueEn.zipWithIndex.foreach {
          case (en, idx) =>
            en := (idx != Param.loadStoreIssuePipelineIndex).B
        }
      }
      case DispatchType.csrOrBranch => {
        io.out.info.issueEn.zipWithIndex.foreach {
          case (en, idx) =>
            en := (idx == Param.csrIssuePipelineIndex).B
        }
      }
      case DispatchType.loadStore => {
        {
          io.out.info.issueEn.zipWithIndex.foreach {
            case (en, idx) =>
              en := (idx == Param.loadStoreIssuePipelineIndex).B
          }
        }
      }
      case _ => throw new Exception("dispatchType error")
    }
  }

}
