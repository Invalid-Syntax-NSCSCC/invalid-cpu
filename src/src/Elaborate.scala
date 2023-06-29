import chisel3.stage._
import circt.stage.{CIRCTTarget, CIRCTTargetAnnotation}

object Elaborate extends App {
  def top = new CoreCpuTop

  val useMFC    = true // Use MLIR-based firrtl compiler
  val generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => top))

  if (useMFC) {
    (new circt.stage.ChiselStage)
      .execute(args, generator :+ CIRCTTargetAnnotation(CIRCTTarget.Verilog))
  } else {
    (new ChiselStage).execute(args, generator)
  }
}
