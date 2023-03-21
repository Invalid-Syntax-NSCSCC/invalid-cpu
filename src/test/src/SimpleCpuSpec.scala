import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import frontend.InstQueue
import pipeline.dispatch.bundles.InstInfoBundle
import utest._

object SimpleCpuSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test ADDI.W") {
      testCircuit(new CoreCpuTop, Seq(WriteVcdAnnotation)) { cpu =>
        cpu.io.intrpt.poke(0.U)
        cpu.io.axi.arready.poke(true.B)
        cpu.io.axi.rvalid.poke(true.B)
        cpu.io.axi.rdata.poke("b_0000001010_000000000001_00000_00001".U) // addi $1, $0, 1
        cpu.clock.step(5)
        cpu.io.axi.rdata.poke("b_0000001010_000000000010_00001_00010".U) // addi $2, $1, 2
        cpu.clock.step(15);
      }
    }
  }
}
