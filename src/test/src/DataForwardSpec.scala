import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import frontend.InstQueue
import pipeline.dispatch.bundles.InstInfoBundle
import utest._

import scala.collection.immutable
// import pipeline.dataforward.DataForwardStage
import pipeline.dataforward.DataForwardStage

 object DataForwardSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test data forward module") {
      testCircuit(new DataForwardStage(3), immutable.Seq(WriteVcdAnnotation)) { dataforward =>
        def wp = dataforward.io.writePorts
        def rp = dataforward.io.readPorts
        rp(0).en.poke(true.B)
        rp(1).en.poke(true.B)
        rp(0).addr.poke(2)
        rp(1).addr.poke(3)

        wp(0).en .poke(true.B);wp(0).addr.poke(2); wp(0).data.poke(4)
        wp(1).en .poke(true.B);wp(1).addr.poke(2); wp(1).data.poke(6)
        wp(2).en .poke(true.B);wp(2).addr.poke(2); wp(2).data.poke(7)

        dataforward.clock.step(1)
        wp(0).en .poke(true.B);wp(0).addr.poke(2); wp(0).data.poke(4)
        wp(1).en .poke(true.B);wp(1).addr.poke(3); wp(1).data.poke(6)
        wp(2).en .poke(false.B);wp(2).addr.poke(2); wp(2).data.poke(7)
        dataforward.clock.step(1)
        wp(0).en .poke(false.B);wp(0).addr.poke(2); wp(0).data.poke(4)
        wp(1).en.poke( true.B);wp(1).addr.poke(3); wp(1).data.poke(6)
        wp(2).en.poke( true.B);wp(2).addr.poke(2); wp(2).data.poke(7)
        dataforward.clock.step(1)

      }
    }
  }
 }
