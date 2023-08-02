package newpipeline.queue.decode

import chisel3._
import newpipeline.queue.bundles.NewDecodePort
import spec._
import pipeline.queue.decode.DispatchType

// object DispatchType extends Enumeration {
//   type Type = Value
//   val common, loadStore, csrOrBranch = Value
// }

abstract class NewDecoder extends Module {
  // A decoder should:
  // 1) Extract and extend immediate from instruction, if it has
  // 2) Extract register information from instruction
  // 3) Something else...

  val io = IO(new NewDecodePort)

  io.out.info.issueEn.zipWithIndex.foreach {
    case (en, idx) =>
      en := false.B
  }

  def selectIssueEn(dispatchType: DispatchType.Type): Unit = {
    dispatchType match {
      case DispatchType.common => {
        io.out.info.issueEn.zipWithIndex.foreach {
          case (en, idx) =>
            en := (idx != 0).B
        }
      }
      case DispatchType.csrOrBranch => {
        io.out.info.issueEn.zipWithIndex.foreach {
          case (en, idx) =>
            en := (idx == 0).B
        }
      }
      case DispatchType.loadStore => {
        {
          io.out.info.issueEn.zipWithIndex.foreach {
            case (en, idx) =>
              en := (idx == 0).B
          }
        }
      }
      case _ => throw new Exception("dispatchType error")
    }
  }

}
