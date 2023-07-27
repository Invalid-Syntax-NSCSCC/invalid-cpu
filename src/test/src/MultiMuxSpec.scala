import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import pipeline.dispatch.bundles.FetchInstInfoBundle
import scala.collection.immutable
import utest._
import enums.TestEnum
import utils.MultiMux2
import chisel3.util.Valid

class TestBundle extends Bundle {
  val ui    = UInt(3.W)
  val enum_ = TestEnum()
  val bool  = Bool()
}
class TestMultiMux extends Module {

  val io = IO(new Bundle {
    val input  = Input(UInt(4.W))
    val output = Output(Valid(new TestBundle))
  })

  def default = 0.U.asTypeOf(new TestBundle)

  val mux = Module(new MultiMux2(2, 2, new TestBundle, default))

  mux.io.inputs.zipWithIndex.foreach {
    case (mux_1, idx_1) =>
      mux_1.zipWithIndex.foreach {
        case (in, idx_2) =>
          val idx = idx_1 * mux_1.length + idx_2
          in.valid     := io.input(idx)
          in.bits.ui   := (idx + 1).U
          in.bits.bool := (idx == 0 || idx == 3).B
          idx match {
            case 0 =>
              in.bits.enum_ := TestEnum.a
            case 1 =>
              in.bits.enum_ := TestEnum.c
            case 2 =>
              in.bits.enum_ := TestEnum.b
            case 3 =>
              in.bits.enum_ := TestEnum.a
          }
      }
  }

  io.output := mux.io.output
}

object MultiMuxSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test multi mux module") {
      testCircuit(new TestMultiMux, immutable.Seq(WriteVcdAnnotation)) { m =>
        m.io.input.poke("b0000".U)
        m.clock.step(1)
        m.io.input.poke("b0001".U)
        m.clock.step(1)
        m.io.input.poke("b0010".U)
        m.clock.step(1)
        m.io.input.poke("b0100".U)
        m.clock.step(1)
        m.io.input.poke("b1000".U)
        m.clock.step(1)
      }
    }
  }
}
