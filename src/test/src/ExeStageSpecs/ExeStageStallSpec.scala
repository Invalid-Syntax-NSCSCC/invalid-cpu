import control.bundles.PipelineControlNdPort
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import pipeline.dispatch.bundles.InstInfoBundle
import utest._
import pipeline.execution.Clz

import scala.util.Random
import pipeline.execution.ExeStage
import spec.ExeInst
import pipeline.dispatch.bundles.ExeInstNdPort
import pipeline.queue.InstQueue
import spec.zeroWord
import spec.Width

import scala.collection.immutable

// object ExeStageStallSpec extends ChiselUtestTester {
//   val tests = Tests {
//     test("Test exe stage stall") {
//       testCircuit(new ExeStage, immutable.Seq(WriteVcdAnnotation)) { exeStage =>
//         val op  = ExeInst.Op.div
//         val sel = ExeInst.Sel.arithmetic
//         val lop = 4756985
//         val rop = 5
//         exeStage.io.pipelineControlPort.poke(PipelineControlNdPort.default)
//         def instPort = exeStage.io.exeInstPort
//         instPort.gprWritePort.en.poke(true.B)
//         instPort.gprWritePort.addr.poke(1.U)
//         instPort.exeOp.poke(op)
//         instPort.exeSel.poke(sel)
//         instPort.jumpBranchAddr.poke(zeroWord)
//         // instPort.pcAddr.poke(zeroWord)
//         instPort.leftOperand.poke(lop.U)
//         instPort.rightOperand.poke(rop.U)

//         exeStage.clock.step(5)
//         exeStage.io.pipelineControlPort.stall.poke(true.B)
//         exeStage.clock.step(15)
//         exeStage.io.pipelineControlPort.stall.poke(false.B)
//         exeStage.clock.step(1)
//         instPort.exeOp.poke(ExeInst.Op.add)
//         exeStage.clock.step(20)
//       }
//     }
//   }
// }
