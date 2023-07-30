// import chisel3._
// import chisel3.util._
// import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
// import chiseltest._
// import chiseltest.simulator.WriteVcdAnnotation
// import pipeline.dispatch.bundles.FetchInstInfoBundle
// import utest._
// import pipeline.execution.Clz
// import pipeline.queue.InstQueue
// import scala.util.Random

// object ComponentSpec extends ChiselUtestTester {
//   val tests = Tests {
//     test("Test clz module") {
//       testCircuit(new Clz, Seq(WriteVcdAnnotation)) { clz =>
//         for ( i<- 0 to 30) {
//           for (j<-0 to 30) {
//             var in = 1
//             for(k<-1 until i) {
//               in = in * 2 +Random.nextInt(2)
//             }
//             if (in >= 0) {
//               var tmp = in
//               var expect = 32
//               while (tmp != 0) {
//                 expect -= 1
//                 tmp /= 2
//               }
//               clz.io.input.poke(in.U)
//               println(s"${i}: ")
//               println(BigInt(in).toString(16))
//               print("expect: ")
//               println(expect)
//               print("res:    ")
//               println(clz.io.output.peek().litValue)
//               println("*************************")
//               clz.io.output.expect(expect.U)
//               clz.clock.step(1)
//             }
//           }
//         }
//       }
//     }
//   }
// }
