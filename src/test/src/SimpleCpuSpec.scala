import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import pipeline.dispatch.bundles.InstInfoBundle
import pipeline.queue.InstQueue
import utest._

// object SimpleCpuSpec extends ChiselUtestTester {
//   val tests = Tests {
//     test("Test instructions") {
//       testCircuit(new CoreCpuTop, Seq(WriteVcdAnnotation)) { cpu =>
//         val instSeq = Seq(
//           "0000001010_000000000011_00000_00001", // addi $1, $0, 3
//           "0000001010_000000110011_00000_00100", // addi $4, $0, 51
//           "0000001010_000000000010_00001_00010", // addi $2, $1, 2
//           // "0000001010_000000000010_00001_00010", // addi $2, $1, 2
//           // "00000000001000000_00001_00100_00110", // div $6, $4, $1
//           "00000000000100100_00010_00001_00011", // slt $3, $1, $2
//           // "00000000000111000_00001_00100_00101", // mul $5, $4, $1
//           // "00000000001000000_00001_00100_00110", // div $6, $4, $1
//           "00000000000111000_00001_00100_00101", // mul $5, $4, $1
//           "00000000001000000_00001_00100_00110", // div $6, $4, $1
//           "00000000001000001_00001_00100_00111", // mod $7, $4, $1
//         )
//         cpu.io.intrpt.poke(0.U)
//         cpu.io.axi.arready.poke(true.B)
//         cpu.io.axi.rvalid.poke(true.B)
//         instSeq.foreach { inst =>
//           cpu.io.axi.rdata.poke(("b" + inst).U)
//           cpu.clock.step(5)
//         }
//         cpu.clock.step(15)
//         cpu.io.axi.rvalid.poke(false.B)
//         cpu.clock.step(15)
//       }
//     }
//   }
// }
