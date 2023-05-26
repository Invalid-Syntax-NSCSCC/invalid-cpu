import chiseltest._
import utest._
import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import memory.DCache
import pipeline.common.BaseStage

import scala.collection.immutable

class DummyInputPort extends Bundle {
  val countDown = UInt(8.W)
  val value     = UInt(32.W)
  val isValid   = Bool()
}

object DummyInputPort {
  def default = 0.U.asTypeOf(new DummyInputPort)
}

class CoworkerPort extends Bundle {
  val combToCoworker   = Output(UInt(8.W))
  val combFromCoworker = Input(UInt(8.W))
}

class DummyStage extends BaseStage(new DummyInputPort, UInt(32.W), DummyInputPort.default, Some(new CoworkerPort)) {
  val counter = RegInit(0.U(8.W))
  counter := Mux(counter === 0.U, 0.U, counter - 1.U)
  when(selectedIn.isValid) {
    when(isLastComputed) {
      counter    := selectedIn.countDown
      isComputed := selectedIn.countDown === 0.U
    }.otherwise {
      isComputed := counter === 1.U // Please note this. It is important for understanding the model and implementing back-to-back stages
    }
    when(isComputed) {
      resultOutReg.valid := true.B
      resultOutReg.bits  := selectedIn.value
    }
  }
  when(io.isFlush) {
    counter := 0.U
  }
  io.peer.get.combToCoworker := counter
  val dummyWire = WireDefault(io.peer.get.combFromCoworker)
}

object BaseStageSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test BaseStage module") {
      testCircuit(
        new DummyStage,
        immutable.Seq(WriteVcdAnnotation)
      ) { stage =>
        def pokeDefault(): Unit = {
          stage.io.in.bits.isValid.poke(false.B)
          stage.io.in.bits.value.poke(0.U)
          stage.io.in.bits.countDown.poke(0.U)
        }

        def pokeCustom(count: UInt, num: UInt): Unit = {
          stage.io.in.bits.isValid.poke(true.B)
          stage.io.in.bits.value.poke(num)
          stage.io.in.bits.countDown.poke(count)
        }

        pokeDefault()
        stage.io.in.valid.poke(false.B)
        stage.io.isFlush.poke(false.B)
        stage.io.out.ready.poke(true.B)

        // Test back to back
        Seq.range(1, 10).foreach { i =>
          stage.clock.step()
          stage.io.in.valid.poke(true.B)
          pokeCustom(0.U, i.U)
          if (i > 1) {
            stage.io.out.ready.poke(true.B)
            stage.io.out.valid.expect(true.B)
            stage.io.out.bits.expect((i - 1).U)
          }
          stage.io.in.ready.expect(true.B)
        }

        // Test peer combination connection
        stage.io.peer.get.combToCoworker.expect(0.U)

        // Test multiple cycles (in valid, out ready)
        val cycles = 3
        val num    = 3
        stage.clock.step()
        stage.io.in.ready.expect(true.B)
        stage.io.in.valid.poke(true.B)
        pokeCustom(cycles.U, num.U)
        stage.io.out.ready.poke(true.B)
        Seq.range(0, cycles).foreach { _ =>
          stage.clock.step()
          stage.io.in.ready.expect(false.B)
          stage.io.out.valid.expect(false.B)
        }
        stage.clock.step()
        stage.io.in.ready.expect(true.B)
        stage.io.in.valid.poke(false.B)
        stage.io.out.ready.poke(true.B)
        stage.io.out.valid.expect(true.B)
        stage.io.out.bits.expect(num.U)

        // Test multiple cycles (in invalid, out ready)
        stage.clock.step()
        stage.io.in.ready.expect(true.B)
        stage.io.in.valid.poke(false.B)
        pokeCustom(cycles.U, num.U)
        stage.io.out.ready.poke(true.B)
        Seq.range(0, 3).foreach { _ =>
          stage.clock.step()
          stage.io.in.ready.expect(true.B)
          stage.io.in.valid.poke(false.B)
          stage.io.out.ready.poke(true.B)
          stage.io.out.valid.expect(false.B)
        }

        // Test multiple cycle (in valid, out not ready then ready)
        stage.clock.step()
        stage.io.in.ready.expect(true.B)
        stage.io.in.valid.poke(true.B)
        pokeCustom(cycles.U, num.U)
        stage.io.out.ready.poke(true.B)
        Seq.range(0, cycles).foreach { _ =>
          stage.clock.step()
          stage.io.in.valid.poke(true.B)
          stage.io.in.ready.expect(false.B)
          stage.io.out.valid.expect(false.B)
        }
        Seq.range(0, 3).foreach { _ =>
          stage.clock.step()
          stage.io.in.valid.poke(true.B)
          stage.io.out.ready.poke(false.B)
          stage.io.in.ready.expect(false.B)
          stage.io.out.valid.expect(true.B)
        }
        stage.clock.step()
        stage.io.in.valid.poke(false.B)
        stage.io.out.ready.poke(true.B)
        stage.io.out.valid.expect(true.B)
        stage.io.out.bits.expect(num.U)
        stage.io.in.ready.expect(false.B)
        Seq.range(0, 3).foreach { _ =>
          stage.clock.step()
          stage.io.out.ready.poke(true.B)
          stage.io.in.valid.poke(false.B)
          stage.io.in.ready.expect(true.B)
          stage.io.out.valid.expect(false.B)
        }

        // Test flush when in multiple cycles computation
        stage.clock.step()
        stage.io.in.ready.expect(true.B)
        stage.io.in.valid.poke(true.B)
        pokeCustom(cycles.U, num.U)
        stage.io.out.ready.poke(true.B)
        stage.clock.step(2)
        stage.io.in.valid.poke(false.B)
        stage.io.isFlush.poke(true.B)
        stage.io.in.ready.expect(false.B)
        stage.io.out.valid.expect(false.B)
        Seq.range(0, 3).foreach { _ =>
          stage.clock.step()
          stage.io.isFlush.poke(false.B)
          stage.io.out.ready.poke(false.B)
          stage.io.in.ready.expect(true.B)
          stage.io.out.valid.expect(false.B)
        }
      }
    }
  }
}
