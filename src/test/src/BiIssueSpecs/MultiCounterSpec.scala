import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import pipeline.dispatch.bundles.InstInfoBundle
import utest._
import control.bundles.PipelineControlNdPort
import pipeline.queue.{BiInstQueue, InstQueue}
import spec.wordLength
import spec.zeroWord
import spec.PipelineStageIndex
import utils.MultiCounter

object MultiCounterSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test multi counter") {
      testCircuit(new MultiCounter(5,4), Seq(WriteVcdAnnotation)) { counter =>
        counter.io.flush poke false.B
        counter.io.inc poke 3
        counter.clock.step(1)
        counter.io.inc poke 3
        counter.clock.step(1)
        counter.io.inc poke 4
        counter.clock.step(1)
        counter.io.inc poke 2
        counter.clock.step(1)
        counter.io.inc poke 2
        counter.clock.step(1)
        counter.io.inc poke 2
        counter.clock.step(1)
        counter.io.flush poke true
      }
    }
  }
}