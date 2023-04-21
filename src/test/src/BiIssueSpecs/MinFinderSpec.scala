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
import utils.MinFinder

object MinFinderSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test MinFinder") {
      testCircuit(new MinFinder(num = 5, wordLength=4), Seq(WriteVcdAnnotation)) { 
        minFinder => if (false) {
          val numsSeq = Seq(
              Seq(1.U,2.U, 4.U, 3.U,5.U),
              Seq(7.U,3.U, 4.U, 3.U,5.U),
              Seq(8.U,2.U, 4.U, 3.U,5.U)
          )
          val masks = (Seq.fill(5)(true.B))

          minFinder.io.masks.zip(masks).foreach{case (dst, src) => dst.poke(src)}
          numsSeq.foreach{nums => 
              minFinder.io.values.zip(nums).foreach{case (dst, src) => dst poke src}
              println(minFinder.io.index.peek())
              minFinder.clock.step(1)
          }
        }
      }
    }
  }
}