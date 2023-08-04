import chisel3.stage._
import circt.stage.{CIRCTTarget, CIRCTTargetAnnotation}

object Elaborate extends App {
  def top = new SimpleCoreCpuTop

  val useMFC    = true // Use MLIR-based firrtl compiler
  val generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => top))

  if (useMFC) {
    (new circt.stage.ChiselStage)
      .execute(args, generator :+ CIRCTTargetAnnotation(CIRCTTarget.SystemVerilog))
  } else {
    (new ChiselStage).execute(args, generator)
  }
}
