package memory

import chisel3._
import chisel3.util._
import control.csrRegsBundles.{AsidBundle, TlbehiBundle, TlbeloBundle, TlbidxBundle}
import memory.bundles.TlbEntryBundle
import memory.enums.TlbMemType
import spec.ExeInst.Op.Tlb._
import spec._

class Tlb extends Module {
  val io = IO(new Bundle {
    val isInvalidate = Input(Bool())
    val isSearch     = Input(Bool())
    val isRead       = Input(Bool())
    val isWrite      = Input(Bool())
    val isFill       = Input(Bool())
    val maintenanceInfo = Input(new Bundle {
      val virtAddr       = UInt(Width.Mem.addr)
      val invalidateInst = UInt(Width.Tlb.op)
    })
    val csr = new Bundle {
      val in = Input(new Bundle {
        val plv      = UInt(2.W)
        val asId     = new AsidBundle
        val tlbehi   = new TlbehiBundle
        val tlbidx   = new TlbidxBundle
        val tlbloVec = Vec(2, new TlbeloBundle)
      })
      val out = new Bundle {
        val tlbidx    = ValidIO(new TlbidxBundle)
        val tlbehi    = ValidIO(new TlbehiBundle)
        val tlbeloVec = Vec(2, ValidIO(new TlbeloBundle))
      }
    }
    val virtAddr = Input(UInt(Width.Mem.addr))
    val memType  = Input(TlbMemType())
    val physAddr = Output(UInt(Width.Mem.addr))
  })

  val tlbEntryVec = RegInit(VecInit(Seq(new TlbEntryBundle)(Param.Count.Tlb.num)))
  tlbEntryVec := tlbEntryVec
  val virtAddrLen = Width.Mem._addr
  val physAddrLen = Width.Mem._addr

  // Fallback
  io.csr.out              := DontCare
  io.csr.out.tlbidx.valid := false.B
  io.csr.out.tlbehi.valid := false.B
  io.csr.out.tlbeloVec.foreach(_.valid := false.B)

  // Lookup & Maintenance: Search
  val isFoundVec = VecInit(Seq.fill(Param.Count.Tlb.num)(false.B))
  tlbEntryVec.zip(isFoundVec).foreach {
    case (entry, isFound) =>
      val is4KbPageSize = entry.compare.pageSize === Value.Tlb.Ps._4Kb
      isFound := entry.compare.isExisted && (
        entry.compare.isGlobal || (entry.compare.asId === io.csr.in.asId.asid)
      ) && Mux(
        io.isSearch,
        entry.compare.virtPageNum === io.csr.in.tlbehi.vppn,
        Mux(
          is4KbPageSize,
          entry.compare.virtPageNum(virtAddrLen - 1, Value.Tlb.Ps._4Kb.litValue + 1) ===
            io.virtAddr(virtAddrLen - 1, Value.Tlb.Ps._4Kb.litValue + 1),
          entry.compare.virtPageNum(virtAddrLen - 1, Value.Tlb.Ps._4Mb.litValue + 1) ===
            io.virtAddr(virtAddrLen - 1, Value.Tlb.Ps._4Mb.litValue + 1)
        )
      )
  }
  val selectedIndex = OHToUInt(isFoundVec)
  val selectedEntry = tlbEntryVec(selectedIndex)
  val selectedPage = Mux(
    io.virtAddr(selectedEntry.compare.pageSize) === 0.U,
    selectedEntry.trans(0),
    selectedEntry.trans(1)
  )
  val isFound = isFoundVec.asUInt.orR
  io.csr.out.tlbidx.valid := io.isSearch
  when(isFound) {
    io.csr.out.tlbidx.bits.index := selectedIndex
    io.csr.out.tlbidx.bits.ne    := false.B
  }.otherwise {
    io.csr.out.tlbidx.bits.ne := true.B
  }

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
  }.elsewhen(selectedPage.plv < io.csr.in.plv) {
    // TODO: Not enough privilege exception
  }.elsewhen(!selectedPage.isDirty && io.memType === TlbMemType.store) {
    // TODO: Page modification exception
  }

  // Maintenance: Read
  val readEntry = tlbEntryVec(io.csr.in.tlbidx.index)
  when(io.isRead) {
    io.csr.out.tlbehi.bits.vppn := readEntry.compare.virtPageNum
    io.csr.out.tlbeloVec.map(_.bits).zip(readEntry.trans).foreach {
      case (tlbelo, trans) =>
        tlbelo.v   := trans.isValid
        tlbelo.d   := trans.isDirty
        tlbelo.plv := trans.plv
        tlbelo.mat := trans.mat
        tlbelo.g   := readEntry.compare.isGlobal
        tlbelo.ppn := trans.physPageNum
    }
    io.csr.out.tlbidx.bits.ps := readEntry.compare.pageSize
    io.csr.out.tlbidx.valid   := true.B
    when(readEntry.compare.isExisted) {
      io.csr.out.tlbidx.bits.ne := false.B
      io.csr.out.tlbehi.valid   := true.B
      io.csr.out.tlbeloVec.foreach(_.valid := true.B)
    }.otherwise {
      io.csr.out.tlbidx.bits.ne := true.B
      io.csr.out.tlbidx.bits.ps := 0.U
    }
  }

  // Maintenance: Write & Fill
  val fillIndex = PriorityEncoder(tlbEntryVec.map(!_.compare.isExisted))
  val writeEntry = tlbEntryVec(
    Mux(
      io.isWrite,
      io.csr.in.tlbidx.index,
      fillIndex
    )
  )
  when(io.isWrite || io.isFill) {
    writeEntry.compare.isExisted := !io.csr.in.tlbidx.ne

    writeEntry.compare.pageSize    := io.csr.in.tlbidx.ps
    writeEntry.compare.virtPageNum := io.csr.in.tlbehi.vppn
    writeEntry.compare.isGlobal    := io.csr.in.tlbloVec(0).g
    // Question: Should write ASID or not

    writeEntry.trans.zip(io.csr.in.tlbloVec).foreach {
      case (trans, tlblo) =>
        trans.plv         := tlblo.plv
        trans.mat         := tlblo.mat
        trans.isDirty     := tlblo.d
        trans.isValid     := tlblo.v
        trans.physPageNum := tlblo.ppn
    }
  }

  // Maintenance: Invalidate
  def invalidateEntry(entry: TlbEntryBundle): Unit = {
    entry.compare.isExisted := false.B
    entry.trans.foreach(_.isValid := false.B)
  }
  def isAsIdMatched(entry: TlbEntryBundle) = entry.compare.asId === io.csr.in.asId.asid
  def isVirtAddrMatched(entry: TlbEntryBundle) =
    Mux(
      entry.compare.pageSize === Value.Tlb.Ps._4Kb,
      entry.compare.virtPageNum(virtAddrLen - 1, Value.Tlb.Ps._4Kb.litValue + 1) ===
        io.maintenanceInfo.virtAddr(virtAddrLen - 1, Value.Tlb.Ps._4Kb.litValue + 1),
      entry.compare.virtPageNum(virtAddrLen - 1, Value.Tlb.Ps._4Mb.litValue + 1) ===
        io.maintenanceInfo.virtAddr(virtAddrLen - 1, Value.Tlb.Ps._4Mb.litValue + 1)
    )
  when(io.isInvalidate) {
    switch(io.maintenanceInfo.invalidateInst) {
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
          when(!entry.compare.isGlobal && isAsIdMatched(entry)) {
            invalidateEntry(entry)
          }
        }
      }
      is(clrNGloblAsIdVa) {
        tlbEntryVec.foreach { entry =>
          when(
            !entry.compare.isGlobal &&
              isAsIdMatched(entry) &&
              isVirtAddrMatched(entry)
          ) {
            invalidateEntry(entry)
          }
        }
      }
      is(clrGloblAsIdVa) {
        tlbEntryVec.foreach { entry =>
          when(
            entry.compare.isGlobal &&
              isAsIdMatched(entry) &&
              isVirtAddrMatched(entry)
          ) {
            invalidateEntry(entry)
          }
        }
      }
    }
  }
}
