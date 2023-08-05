import chisel3.stage._
import circt.stage.{CIRCTTarget, CIRCTTargetAnnotation}
import spec.Param

object Elaborate extends App {

  var generator: Seq[chisel3.stage.ChiselGeneratorAnnotation] = Seq()

  if (Param.useSimpleBackend) {
    class CoreCpuTop extends SimpleCoreCpuTop
    generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => new CoreCpuTop))
  } else {
    class CoreCpuTop extends ComplexCoreCpuTop
    generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => new CoreCpuTop))
  }

  val useMFC = true // Use MLIR-based firrtl compiler
  // val generator = Seq(chisel3.stage.ChiselGeneratorAnnotation(() => top))

  if (useMFC) {
    (new circt.stage.ChiselStage)
      .execute(args, generator :+ CIRCTTargetAnnotation(CIRCTTarget.SystemVerilog))
  } else {
    (new ChiselStage).execute(args, generator)
  }
}
