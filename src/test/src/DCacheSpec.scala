import chiseltest._
import pipeline.mem.DCache
import utest._
import chisel3._
import chisel3.util._
import pipeline.mem.bundles.StatusTagBundle
import pipeline.mem.enums.ReadWriteSel
import spec.Param

import scala.collection.immutable

object DCacheSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test DCache module") {
      val _debugAddrSeq = Seq(
        "b_000_001",
        "b_000_010",
        "b_000_011"
      )
      val debugAddrSeq = _debugAddrSeq.map(_.U(Param.Width.DCache.addr))

      val debugDataLineSeq = Seq(
        "h_0000000F_000000FF_00000FFF_0000FFFF".U(Param.Width.DCache.dataLine),
        "h_0000000F_000000FF_00000EEE_0000FFFF".U(Param.Width.DCache.dataLine),
        "h_0000000F_000000FF_00000DDD_0000FFFF".U(Param.Width.DCache.dataLine)
      )

      // Width: 22 + 2
      val _debugStatusTagSeq = Seq(
        "b_10_0000000000000000111100",
        "b_10_0000000000000000111101",
        "b_10_0000000000000000111110"
      )
      val debugStatusTagSeq = _debugStatusTagSeq.map(_.U(StatusTagBundle.width.W))

      val _byteOffset = "b_01_00"

      val debugSetNumSeq = Seq(
        0,
        0,
        1
      )

      val memAddrSeq = _debugStatusTagSeq.lazyZip(_debugAddrSeq).map {
        case (st, addr) =>
          val tagS     = st.slice(5, st.length)
          val addrS    = addr.slice(2, addr.length)
          val offsetS  = _byteOffset.slice(2, _byteOffset.length)
          val memAddrS = "b" + tagS + addrS + offsetS
          memAddrS.U(spec.Width.Mem.data)
      }

      testCircuit(
        new DCache(true, debugAddrSeq, debugDataLineSeq, debugStatusTagSeq, debugSetNumSeq),
        immutable.Seq(WriteVcdAnnotation)
      ) { cache =>
        // Wait for cache debug init
        cache.io.accessPort.isValid.poke(false.B)
        cache.clock.step(2)

        // Test read (cache hit)
        debugDataLineSeq.lazyZip(memAddrSeq).foreach {
          case (dl, mAddr) =>
            // Request
            val byteOffset = _byteOffset.U(Param.Width.DCache.byteOffset)
            val data       = dl(32 * (byteOffset.litValue / 4 + 1) - 1, 32 * (byteOffset.litValue / 4))

            cache.io.accessPort.isValid.poke(true.B)
            cache.io.accessPort.rw.poke(ReadWriteSel.read)
            cache.io.accessPort.addr.poke(mAddr)

            cache.clock.step()

            // Get data
            cache.io.accessPort.read.isValid.expect(true.B)
            println(f"Read data: ${cache.io.accessPort.read.data.peekInt()}%X")
            cache.io.accessPort.read.data.expect(data)
        }

        cache.io.accessPort.isValid.poke(false.B)
        cache.clock.step()

        // Test write (cache hit)
        memAddrSeq.foreach { mAddr =>
          // Request
          val data = "h_AAAA_AAAA".U
          val mask = "h_F0F0_FFFF".U
          cache.io.accessPort.isValid.poke(true.B)
          cache.io.accessPort.rw.poke(ReadWriteSel.write)
          cache.io.accessPort.addr.poke(mAddr)
          cache.io.accessPort.write.data.poke(data)
          cache.io.accessPort.write.mask.poke(mask)

          cache.clock.step()

          // Will complete write
          cache.io.accessPort.write.isComplete.expect(true.B)

          // Allow next write
          cache.clock.step()
        }

        cache.clock.step(5)
      }
    }
  }
}
