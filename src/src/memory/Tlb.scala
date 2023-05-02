package memory

import chisel3._
import chisel3.util._
import memory.bundles.TlbEntryBundle
import memory.enums.TlbMemType
import spec._
import ExeInst.Op.Tlb._

class Tlb extends Module {
  val io = IO(new Bundle {
    val invalidate = Input(new Bundle {
      val isValid  = Bool()
      val inst     = UInt(Width.Tlb.op)
      val asId     = UInt(10.W)
      val virtAddr = Input(UInt(Width.Mem.addr))
    })
    val virtAddr = Input(UInt(Width.Mem.addr))
    val memType  = Input(TlbMemType())
    val plv      = Input(UInt(2.W))
    val asId     = Input(UInt(10.W))
    val physAddr = Output(UInt(Width.Mem.addr))
  })

  val tlbEntryVec = RegInit(VecInit(Seq(new TlbEntryBundle)(Param.Count.Tlb.num)))
  tlbEntryVec := tlbEntryVec
  val virtAddrLen = Width.Mem._addr
  val physAddrLen = Width.Mem._addr

  // Lookup
  val isFoundVec = WireDefault(Vec(Param.Count.Tlb.num, false.B))
  tlbEntryVec.zip(isFoundVec).foreach {
    case (entry, isFound) =>
      isFound := entry.compare.isExisted && (
        entry.compare.isGlobal || (entry.compare.asId === io.asId)
      ) && (
        entry.compare.virtPageNum(virtAddrLen - 1, entry.compare.pageSize + 1) ===
          io.virtAddr(virtAddrLen - 1, entry.compare.pageSize + 1)
      )
  }
  val selectedIndex = OHToUInt(isFoundVec)
  val selectedEntry = tlbEntryVec(selectedIndex)
  val selectedPage = Mux(
    io.virtAddr(selectedEntry.compare.pageSize) === 0,
    selectedEntry.trans(0),
    selectedEntry.trans(1)
  )

  // Translate
  io.physAddr := Mux(
    selectedEntry.compare.pageSize === Value.Tlb.Ps._4Kb,
    Cat(
      selectedPage.physPageNum(physAddrLen - 1, Value.Tlb.Ps._4Kb.litValue),
      io.virtAddr(Value.Tlb.Ps._4Kb.litValue, 0)
    ),
    Cat(
      selectedPage.physPageNum(physAddrLen - 1, Value.Tlb.Ps._4Mb.litValue),
      io.virtAddr(Value.Tlb.Ps._4Mb.litValue, 0)
    )
  )

  // Handle exception
  val isFound = isFoundVec.asUInt.orR
  when(!isFound) {
    // TODO: TLB reentry exception
  }
  when(!selectedPage.isValid) {
    switch(io.memType) {
      // TODO: Page invalid exception
      is(TlbMemType.load) {}
      is(TlbMemType.store) {}
      is(TlbMemType.fetch) {}
    }
  }.elsewhen(selectedPage.plv < io.plv) {
    // TODO: Not enough privilege exception
  }.elsewhen(!selectedPage.isDirty && io.memType === TlbMemType.store) {
    // TODO: Page modification exception
  }

  // Maintenance: Invalidate
  def invalidateEntry(entry: TlbEntryBundle): Unit = {
    entry.compare.isExisted := false.B
    entry.trans.foreach(_.isValid := false.B)
  }
  when(io.invalidate.isValid) {
    switch(io.invalidate.inst) {
      is(clrAll, clrAllAlt) {
        tlbEntryVec.foreach(invalidateEntry)
      }
      is(clrGlobl) {
        tlbEntryVec.foreach { entry =>
          when(entry.compare.isGlobal) {
            invalidateEntry(entry)
          }
        }
      }
      is(clrNGlobl) {
        tlbEntryVec.foreach { entry =>
          when(!entry.compare.isGlobal) {
            invalidateEntry(entry)
          }
        }
      }
      is(clrNGloblAsId) {
        tlbEntryVec.foreach { entry =>
          when(
            !entry.compare.isGlobal &&
              entry.compare.asId === io.invalidate.asId
          ) {
            invalidateEntry(entry)
          }
        }
      }
      is(clrNGloblAsIdVa) {
        tlbEntryVec.foreach { entry =>
          when(
            !entry.compare.isGlobal &&
              entry.compare.asId === io.invalidate.asId &&
              entry.compare.virtPageNum(virtAddrLen - 1, entry.compare.pageSize + 1) ===
              io.invalidate.virtAddr(virtAddrLen - 1, entry.compare.pageSize + 1)
          ) {
            invalidateEntry(entry)
          }
        }
      }
      is(clrGloblAsIdVa) {

        is(clrGloblAsIdVa) {
          tlbEntryVec.foreach { entry =>
            when(
              entry.compare.isGlobal &&
                entry.compare.asId === io.invalidate.asId &&
                entry.compare.virtPageNum(virtAddrLen - 1, entry.compare.pageSize + 1) ===
                io.invalidate.virtAddr(virtAddrLen - 1, entry.compare.pageSize + 1)
            ) {
              invalidateEntry(entry)
            }
          }
        }
      }
    }
  }
}
