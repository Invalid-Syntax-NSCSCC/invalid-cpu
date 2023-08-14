import chisel3.stage._
import circt.stage.{CIRCTTarget, CIRCTTargetAnnotation}
import spec.Param

object Elaborate extends App {

  val generator = if (Param.useSimpleBackend) {
    class CoreCpuTop extends SimpleCoreCpuTop
    Seq(chisel3.stage.ChiselGeneratorAnnotation(() => new CoreCpuTop))
  } else {
    class CoreCpuTop extends ComplexCoreCpuTop
    Seq(chisel3.stage.ChiselGeneratorAnnotation(() => new CoreCpuTop))
  }

  val useMFC = true // Use MLIR-based firrtl compiler

  if (useMFC) {
    (new circt.stage.ChiselStage)
      .execute(args, generator :+ CIRCTTargetAnnotation(CIRCTTarget.SystemVerilog))
  } else {
    (new ChiselStage).execute(args, generator)
  }
}
