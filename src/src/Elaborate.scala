import chisel3.stage._
import circt.stage.{CIRCTTarget, CIRCTTargetAnnotation}
import pipeline.dispatch.IssueStage

object Elaborate extends App {
  val useMFC    = true // Use MLIR-based firrtl compiler
  val generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => top))
  def top       = new TestType

  if (useMFC) {
    (new circt.stage.ChiselStage)
      .execute(args, generator :+ CIRCTTargetAnnotation(CIRCTTarget.Verilog))
  } else {
    (new ChiselStage).execute(args, generator)
  }
}
