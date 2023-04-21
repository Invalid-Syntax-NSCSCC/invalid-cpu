import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import frontend.InstQueue
import pipeline.dispatch.bundles.InstInfoBundle
import utest._
import control.bundles.PipelineControlNDPort
import spec.wordLength
import spec.zeroWord
import frontend.BiInstQueue
import spec.PipelineStageIndex

// object BiInstQueueSpec extends ChiselUtestTester {
//   val tests = Tests {
//     test("Test bi inst queue") {
//       testCircuit(new BiInstQueue(queueLength=5, issueNum=2), Seq(WriteVcdAnnotation)) { instQueue =>
//         val seq = Seq(
//             // pc0, in0, inst0, pc1,  in1, inst1, out0, out1
//             ("h04".U, true.B, "ha1".U, "h08".U, false.B, "ha2".U, false.B, false.B),
//             ("h0c".U, true.B, "ha3".U, "h10".U, true.B, "ha4".U, true.B, true.B),
//             ("h14".U, true.B, "ha5".U, "h18".U, true.B, "ha6".U, false.B, false.B),
//             ("h1c".U, true.B, "ha7".U, "h20".U, false.B, "ha8".U, true.B, true.B),
//             ("h24".U, true.B, "ha9".U, "h28".U, false.B, "haa".U, false.B, false.B),
//             ("h2c".U, true.B, "hab".U, "h30".U, false.B, "hac".U, false.B, false.B),
//             ("h34".U, true.B, "had".U, "h38".U, false.B, "hae".U, false.B, false.B)
//         )

//         for ((pc0, in0, inst0, pc1, in1, inst1, out0, out1) <- seq) {
//             instQueue.io.enqueuePorts(0).valid poke in0
//             instQueue.io.enqueuePorts(0).bits.inst poke inst0
//             instQueue.io.enqueuePorts(0).bits.pcAddr poke pc0

//             instQueue.io.enqueuePorts(1).valid poke in1
//             instQueue.io.enqueuePorts(1).bits.inst poke inst1
//             instQueue.io.enqueuePorts(1).bits.pcAddr poke pc1

//             instQueue.io.dequeuePorts(0).ready poke out0
//             instQueue.io.dequeuePorts(1).ready poke out1

//             instQueue.clock.step(1)
//             println(s"********************")
//             println(s"(0) valid: ${instQueue.io.dequeuePorts(0).valid.peek()}; inst: ${instQueue.io.dequeuePorts(0).bits.instInfo.inst.peek()}")
//             // println(s"${instQueue.io.debugPort(0).inst.peek()}")
//             println(s"(1) valid: ${instQueue.io.dequeuePorts(1).valid.peek()}; inst: ${instQueue.io.dequeuePorts(1).bits.instInfo.inst.peek().litValue.toString(16)}")
//             // println(s"${instQueue.io.debugPort(1).inst.peek()}")
        
//           }
//       }
//     }
//   }
// }