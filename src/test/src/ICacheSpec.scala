import chiseltest._
import utest._
import chisel3._
import chisel3.util._
import common.enums.ReadWriteSel
import memory.ICache
import memory.bundles.ICacheStatusTagBundle
import spec.Param

import scala.collection.immutable

object ICacheSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test ICache module") {
      val _debugAddrSeq = Seq(
        "b_000_001",
        "b_000_010",
        "b_000_011"
      )
      val debugAddrSeq = _debugAddrSeq.map(_.U(Param.Width.ICache.addr))

      def getDataLine(str: String) = ("h_" + "0" * 0 + str).U(Param.Width.ICache.dataLine)

      val debugDataLineSeq = Seq(
        getDataLine("0000000F_000000FF_00000FFF_0000FFFF"),
        getDataLine("0000000F_000000FF_00000EEE_0000FFFF"),
        getDataLine("0000000F_000000FF_00000DDD_0000FFFF")
      )

      // Width: 22 + 1
      val _debugStatusTagSeq = Seq(
        "b_1_0000000000000000111100",
        "b_1_0000000000000000111101",
        "b_1_0000000000000000111110"
      )
      val debugStatusTagSeq = _debugStatusTagSeq.map(_.U(ICacheStatusTagBundle.width.W))

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

      // Query index: 1
      val readRefillMemAddr   = "b_1000000000000000111100_000001_0100".U
      // val readRefillWbMemAddr = "b_1100000000000000111100_000001_0100".U

      // Query index: 2
      // val writeRefillMemAddr   = "b_1000000000000000111101_000010_0100".U
      // val writeRefillWbMemAddr = "b_1100000000000000111101_000010_0100".U

      val memReadDataSeq        = Seq("h_F".U, "h_FF".U, "h_FFF".U, "h_FFFF".U)
      val anotherMemReadDataSeq = Seq("h_E".U, "h_EE".U, "h_EEE".U, "h_EEEE".U)

      testCircuit(
        new ICache(true, debugAddrSeq, debugDataLineSeq, debugStatusTagSeq, debugSetNumSeq),
        immutable.Seq(WriteVcdAnnotation)
      ) { cache =>
        // Wait for cache debug init
        cache.io.accessPort.req.client.isValid.poke(false.B)
        cache.clock.step(2)

        // Test read (cache hit)
        debugDataLineSeq.lazyZip(memAddrSeq).foreach {
          case (dl, mAddr) =>
            // Request
            val byteOffset = _byteOffset.U(Param.Width.ICache.byteOffset)
            val data       = dl(32 * (byteOffset.litValue / 4 + 1) - 1, 32 * (byteOffset.litValue / 4))

            cache.io.accessPort.req.client.isValid.poke(true.B)
            // cache.io.accessPort.req.client.rw.poke(ReadWriteSel.read)
            cache.io.accessPort.req.client.addr.poke(mAddr)

            cache.clock.step()

            // Get data
            cache.io.accessPort.res.isComplete.expect(true.B)
            println(f"Read data: 0x${cache.io.accessPort.res.read.data.peekInt()}%08X")
            cache.io.accessPort.res.read.data.expect(data)
        }
        println("✅ All read operations completed.\n")

        cache.io.accessPort.req.client.isValid.poke(false.B)
        cache.clock.step()


       // Test read (miss, no write back)
        cache.io.axiMasterPort.ar.ready.poke(false.B)
        cache.io.axiMasterPort.r.valid.poke(false.B)
        cache.io.axiMasterPort.r.bits.last.poke(false.B)
        cache.io.axiMasterPort.r.bits.resp.poke(spec.Value.Axi.Response.Okay)
        cache.io.axiMasterPort.b.bits.resp.poke(spec.Value.Axi.Response.Okay)
        cache.io.axiMasterPort.aw.ready.poke(false.B)
        cache.io.axiMasterPort.w.ready.poke(false.B)
        cache.io.axiMasterPort.b.valid.poke(false.B)


        cache.io.accessPort.req.client.isValid.poke(true.B)
        // cache.io.accessPort.req.client.rw.poke(ReadWriteSel.read)
        cache.io.accessPort.req.client.addr.poke(readRefillMemAddr)

        cache.clock.step()

        cache.io.accessPort.res.isComplete.expect(false.B)

        cache.clock.step()

        cache.io.axiMasterPort.ar.valid.expect(true.B)
        var burstMax = cache.io.axiMasterPort.ar.bits.len.peekInt()
        cache.io.axiMasterPort.ar.ready.poke(true.B)
        cache.io.axiMasterPort.ar.bits.addr.expect(readRefillMemAddr)

        cache.clock.step()

        cache.io.accessPort.req.client.isValid.poke(false.B)
        cache.io.axiMasterPort.ar.ready.poke(false.B)
        for (i <- 0 to burstMax.toInt) {
          cache.io.axiMasterPort.r.ready.expect(true.B)
          cache.io.axiMasterPort.r.valid.poke(true.B)
          cache.io.axiMasterPort.r.bits.data.poke(memReadDataSeq(i % memReadDataSeq.length))
          cache.io.axiMasterPort.r.ready.expect(true.B)

          if (i == burstMax.toInt) {
            cache.io.axiMasterPort.r.bits.last.poke(true.B)
          }

          cache.clock.step()
        }

     cache.io.axiMasterPort.r.valid.poke(false.B)
        cache.io.axiMasterPort.r.bits.last.poke(false.B)
        cache.io.accessPort.res.isComplete.expect(true.B)
        println(f"Read data: 0x${cache.io.accessPort.res.read.data.peekInt()}%08X")
        cache.io.accessPort.res.read.data.expect(memReadDataSeq(1))

        println("✅ Read (miss, no write back) operation completed.\n")

        cache.clock.step()

        // println("Note: Please check waveform to confirm correctness")
        // println("✅ Read (miss, write back) operation completed.\n")

        cache.clock.step(5)
      }
    }
  }
}
