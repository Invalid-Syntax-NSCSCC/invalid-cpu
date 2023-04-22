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
import pipeline.execution.Mul
import pipeline.execution.Div
import spec.ExeInst


// object MulSpec extends ChiselUtestTester {
//   val tests = Tests {
//     test("Test div module") {
//       testCircuit(new Mul, Seq(WriteVcdAnnotation)) { mul =>
//         val lops = Seq(
//           10, 0, 3, 754, 7962, 785424, 742, 427
//         )
//         val rops = Seq(
//           10, 3, 10, 27, 0, 1234, 752, 426
//         )
//         var i = 0
//         for((lop, rop) <- lops.zip(rops)) {
//           i+=1
//           mul.io.mulInst.bits.op.poke(ExeInst.Op.mul)
//           mul.io.mulInst.bits.leftOperand.poke(lop.U)
//           mul.io.mulInst.bits.rightOperand.poke(rop.U(32.W))
//           mul.io.mulInst.valid.poke(true.B)
//           mul.clock.step(1)
//           mul.io.mulInst.valid.poke(false.B)
//           mul.clock.step(2)
//           println("***********************************")
//           println(s"idx ${i}: ${lop} * ${rop}")
//           if (rop != 0) {
//             println(s"expect: ${lop*rop}")
//           }
//           println(s"pred  : ${mul.io.mulResult.bits.peek().litValue}")
//         }
//         // var lop = 15.U
//         // var divisor = 2.U


//       }
//     }
//   }
// }
