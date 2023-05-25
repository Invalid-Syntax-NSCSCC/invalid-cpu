import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import pipeline.dispatch.bundles.InstInfoBundle
import utest._
import control.bundles.PipelineControlNdPort
import pipeline.queue.InstQueue
import spec.wordLength
import spec.zeroWord

// object InstQueueSpec extends ChiselUtestTester {
//   val tests = Tests {
//     test("Test inst queue") {
//       testCircuit(new InstQueue, Seq(WriteVcdAnnotation)) { instQueue =>
//         instQueue.io.pipelineControlPort poke PipelineControlNdPort.default
//         instQueue.io.enqueuePort.bits.inst.poke(false.B)
//             instQueue.io.enqueuePort.bits.pcAddr.poke(false.B)
//         val pcs = Seq.range(0,8).map((4*_)).map(_.U)
//         val enqSeq = Seq(
//           zeroWord,
//           "had123456".U(wordLength.W),
//           "had234567".U(wordLength.W),
//           "had456789".U(wordLength.W),
//           "hadaaaaaa".U(wordLength.W),
//           "had777777".U(wordLength.W),
//           zeroWord,
//           zeroWord
//         )
//         val enqValid = Seq(
//           false.B,
//           true.B,
//           true.B,
//           true.B,
//           true.B,
//           false.B,
//           false.B,
//           false.B
//         )
//         val deqReady = Seq(
//           false.B,
//           false.B,
//           false.B,
//           true.B,
//           true.B,
//           true.B,
//           true.B,
//           true.B,
//         )

//         pcs lazyZip enqSeq lazyZip enqValid lazyZip deqReady foreach {case (pc ,bit, in, out) =>
//             println ("**************")
//             println (pc)
//             println (bit)
//             println (in)
//             println (out)
//             instQueue.io.enqueuePort.bits.inst.poke(bit)
//             instQueue.io.enqueuePort.bits.pcAddr.poke(pc)
//             instQueue.io.enqueuePort.valid.poke(in)
//             instQueue.io.dequeuePort.ready.poke(out)
//             instQueue.clock.step(1)
//         }
//       }
//     }
//   }
// }