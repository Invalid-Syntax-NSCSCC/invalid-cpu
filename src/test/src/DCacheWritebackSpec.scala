//import chiseltest._
//import utest._
//import chisel3._
//import chisel3.util._
//import common.enums._
//import memory.DCache
//import memory.bundles._
//import pipeline.memory.enums._
//import scala.collection.immutable
//
//object DCacheWritebackSpec extends ChiselUtestTester {
//  val tests = Tests {
//    test("Test DCache write-back") {
//      val targetAddrs = Seq(
//        "h_A0AAAAA0",
//        "h_B0AAAAA2",
//        "h_C0AAAAA3"
//      ).map(_.U(32.W))
//      val expectedDatas = Seq(
//        "h_11111111",
//        "h_22222222",
//        "h_33333333"
//      ).map(_.U(32.W))
//
//      testCircuit(
//        new DCache(false),
//        immutable.Seq(WriteVcdAnnotation)
//      ) { cache =>
//        cache.clock.step()
//        cache.io.axiMasterPort.ar.ready.poke(true.B)
//        cache.io.axiMasterPort.r.valid.poke(true.B)
//        cache.io.axiMasterPort.r.bits.data.poke("h_FFFFFFFF".U)
//        cache.io.axiMasterPort.r.bits.last.poke(true.B)
//        cache.io.axiMasterPort.r.bits.id.poke(0.U)
//        cache.io.axiMasterPort.r.bits.resp.poke(0.U)
//        cache.io.axiMasterPort.r.bits.user.poke(0.U)
//        cache.io.axiMasterPort.aw.ready.poke(true.B)
//        cache.io.axiMasterPort.w.ready.poke(true.B)
//        cache.io.axiMasterPort.b.valid.poke(true.B)
//        cache.io.axiMasterPort.b.bits.id.poke(0.U)
//        cache.io.axiMasterPort.b.bits.resp.poke(0.U)
//        cache.io.axiMasterPort.b.bits.user.poke(0.U)
//
//        cache.io.maintenancePort.client.addr.poke(targetAddrs(0))
//        cache.io.maintenancePort.client.control.isCoherentByHit.poke(false.B)
//        cache.io.maintenancePort.client.control.isCoherentByIndex.poke(false.B)
//        cache.io.maintenancePort.client.control.isInit.poke(false.B)
//        cache.io.maintenancePort.client.control.isL1Valid.poke(false.B)
//        cache.io.maintenancePort.client.control.isL2Valid.poke(false.B)
//
//        cache.io.accessPort.req.client.isValid.poke(false.B)
//        cache.io.accessPort.req.client.rw.poke(ReadWriteSel.write)
//        cache.io.accessPort.req.client.addr.poke(targetAddrs(0))
//        cache.io.accessPort.req.client.mask.poke("b_1111".U)
//        cache.io.accessPort.req.client.read.isUnsigned.poke(false.B)
//        cache.io.accessPort.req.client.read.size.poke(MemSizeType.word)
//        cache.io.accessPort.req.client.write.data.poke("h_A0A0A0A0".U)
//
//        cache.clock.step()
//
//        // Write cache (not hit)
//        cache.io.accessPort.req.client.addr.poke(targetAddrs(0))
//        cache.io.axiMasterPort.r.bits.data.poke(expectedDatas(0))
//        cache.io.accessPort.req.client.write.data.poke(expectedDatas(0))
//        cache.io.accessPort.req.client.rw.poke(ReadWriteSel.write)
//        cache.io.accessPort.req.client.isValid.poke(true.B)
//        cache.io.accessPort.req.isReady.expect(true.B)
//        cache.clock.step()
//        cache.io.accessPort.req.client.isValid.poke(false.B)
//        while (!cache.io.accessPort.res.isComplete.peekBoolean()) {
//          cache.clock.step()
//        }
//        cache.clock.step()
//
//        // Write cache (not hit)
//        cache.io.accessPort.req.client.addr.poke(targetAddrs(1))
//        cache.io.axiMasterPort.r.bits.data.poke(expectedDatas(1))
//        cache.io.accessPort.req.client.write.data.poke(expectedDatas(1))
//        cache.io.accessPort.req.client.rw.poke(ReadWriteSel.write)
//        cache.io.accessPort.req.client.isValid.poke(true.B)
//        cache.io.accessPort.req.isReady.expect(true.B)
//        cache.clock.step()
//        cache.io.accessPort.req.client.isValid.poke(false.B)
//        while (!cache.io.accessPort.res.isComplete.peekBoolean()) {
//          cache.clock.step()
//        }
//        cache.clock.step()
//
//        // Write cache (not hit, write back)
//        cache.io.accessPort.req.client.addr.poke(targetAddrs(2))
//        cache.io.axiMasterPort.r.bits.data.poke(expectedDatas(2))
//        cache.io.accessPort.req.client.write.data.poke(expectedDatas(2))
//        cache.io.accessPort.req.client.rw.poke(ReadWriteSel.write)
//        cache.io.accessPort.req.client.isValid.poke(true.B)
//        cache.io.accessPort.req.isReady.expect(true.B)
//        cache.clock.step()
//        cache.io.accessPort.req.client.isValid.poke(false.B)
//        while (!cache.io.accessPort.res.isComplete.peekBoolean()) {
//          cache.clock.step()
//        }
//        cache.clock.step()
//
//        // Read cache (not hit, write back)
//        cache.io.accessPort.req.client.addr.poke(targetAddrs(0))
//        cache.io.axiMasterPort.r.bits.data.poke(expectedDatas(0))
//        cache.io.accessPort.req.client.write.data.poke(expectedDatas(0))
//        cache.io.accessPort.req.client.rw.poke(ReadWriteSel.read)
//        cache.io.accessPort.req.client.isValid.poke(true.B)
//        cache.io.accessPort.req.isReady.expect(true.B)
//        cache.clock.step()
//        cache.io.accessPort.req.client.isValid.poke(false.B)
//        while (!cache.io.accessPort.res.isComplete.peekBoolean()) {
//          cache.clock.step()
//        }
//        cache.clock.step()
//      }
//    }
//  }
//}
