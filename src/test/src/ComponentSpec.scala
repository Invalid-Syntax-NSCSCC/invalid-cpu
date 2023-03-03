import chisel3._
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import frontend.InstQueue
import utest._

object ComponentSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test InstQueue module") {
      testCircuit(new InstQueue, Seq(WriteVcdAnnotation)) { instQueue =>
        // Nothing in queue, then no dequeue
        instQueue.io.dequeuePort.valid.expect(false.B)
        instQueue.io.enqueuePort.ready.expect(true.B)
        instQueue.clock.step()

        // Enqueue and dequeue at the same time, then flow through and can dequeue
        val testValue = 114514.U
        instQueue.io.enqueuePort.ready.expect(true.B)
        instQueue.io.enqueuePort.valid.poke(true.B)
        instQueue.io.enqueuePort.bits.poke(testValue)

        instQueue.io.dequeuePort.ready.poke(true.B)
        instQueue.io.dequeuePort.valid.expect(true.B)
        instQueue.io.dequeuePort.bits.expect(testValue)
        instQueue.clock.step()

        // Fill up the queue, then cannot enqueue
        instQueue.io.dequeuePort.ready.poke(false.B)
        for (i <- 1 to 5) {
          instQueue.io.enqueuePort.valid.poke(true.B)
          instQueue.io.enqueuePort.bits.poke(1.U)
          instQueue.clock.step()
        }
        instQueue.io.enqueuePort.valid.poke(false.B)
        instQueue.io.enqueuePort.ready.expect(false.B)

        // Flush the queue, then cannot dequeue
        instQueue.io.isFlush.poke(true.B)
        instQueue.clock.step()
        instQueue.io.dequeuePort.valid.expect(false.B)
      }
    }
  }
}
