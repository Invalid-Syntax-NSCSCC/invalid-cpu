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
import pipeline.execution.Div
import spec.ExeInst

// object DivSpec extends ChiselUtestTester {
//   val tests = Tests {
//     test("Test div module") {
//       testCircuit(new Div, Seq(WriteVcdAnnotation)) { div =>
//         val dividends = Seq(
//           10, 0, 3, 754, 7962, 785424, 742, 427
//         )
//         val divisors = Seq(
//           10, 3, 10, 27, 0, 1234, 752, 426
//         )
//         var i = 0
//         for((dividend, divisor) <- dividends.zip(divisors)) {
//           i+=1
//           div.io.divInst.bits.op.poke(ExeInst.Op.div)
//           div.io.divInst.bits.leftOperand.poke(dividend.U)
//           div.io.divInst.bits.rightOperand.poke(divisor.U(32.W))
//           div.io.divInst.valid.poke(true.B)
//           div.clock.step(2)
//           div.io.divInst.valid.poke(false.B)
//           div.clock.step(50)
//           println("***********************************")
//           println(s"idx ${i}: ${-dividend} / ${divisor}")
//           if (divisor != 0) {
//             println(s"expect: ${-dividend/divisor} ... ${-dividend%divisor}")
//           }
//           println(s"pred  : ${div.io.divResult.bits.quotient.peek().litValue} ... ${div.io.divResult.bits.remainder.peek().litValue}")
//         }
//         // var dividend = 15.U
//         // var divisor = 2.U

//       }
//     }
//   }
// }
