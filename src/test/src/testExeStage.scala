
import pipeline.ctrl.bundles.PipelineControlNDPort
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import frontend.InstQueue
import pipeline.dispatch.bundles.InstInfoBundle
import utest._
import pipeline.execution.Clz
import scala.util.Random
import pipeline.execution.ExeStage
import spec.ExeInst
import pipeline.dispatch.bundles.ExeInstNdPort



object ExeStageSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test exe stage module") {
      testCircuit(new ExeStage, Seq(WriteVcdAnnotation)) { exeStage =>
        val ops = Seq(
            ExeInst.Op.add,
            ExeInst.Op.slt,
            ExeInst.Op.mul,
            ExeInst.Op.div,
            ExeInst.Op.sub,
            ExeInst.Op.mod,
            ExeInst.Op.add,
            ExeInst.Op.nop
        )
        val sel = ExeInst.Sel.arithmetic
        val lop = 47.U;
        val rop = 0.U;
        exeStage.io.exeInstPort.poke(ExeInstNdPort.default)
        exeStage.io.pipelineControlPort.poke(PipelineControlNDPort.default)
        def instPort = exeStage.io.exeInstPort

        instPort.gprWritePort.en.poke(true.B)
        instPort.gprWritePort.addr.poke(7.U)
        for (i <- 0 until ops.length) {
            instPort.exeOp.poke(ops(i))
            instPort.exeSel.poke(sel)
            instPort.leftOperand.poke(lop)
            instPort.rightOperand.poke(rop)
            
            println(exeStage.io.stallRequest.peek().litValue)
            while (exeStage.io.stallRequest.peek().litValue == 1) {
                print("*")
                exeStage.clock.step(1)
                instPort.exeOp.poke(0.U)
                instPort.exeSel.poke(0.U)
                instPort.leftOperand.poke(0.U)
                instPort.rightOperand.poke(0.U)
                
            }
            println()
            exeStage.clock.step(1) 
        }
        exeStage.clock.step(10)
      }
    }
  }
}
