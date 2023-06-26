package memory

import chisel3._
import chisel3.util._
import control.csrBundles.{AsidBundle, EstatBundle, TlbehiBundle, TlbeloBundle, TlbidxBundle}
import memory.bundles._
import memory.enums.TlbMemType
import pipeline.commit.bundles.DifftestTlbFillNdPort
import spec.ExeInst.Op.Tlb._
import spec._
import spec.Param.isDiffTest

class Tlb extends Module {
  val io = IO(new Bundle {
    val maintenanceInfo = Input(new TlbMaintenanceNdPort)
    val csr = new Bundle {
      val in = Input(new Bundle {
        val plv      = UInt(2.W)
        val asId     = new AsidBundle
        val tlbehi   = new TlbehiBundle
        val tlbidx   = new TlbidxBundle
        val tlbloVec = Vec(2, new TlbeloBundle)
        val estat    = new EstatBundle
      })
      val out = Output(new TlbCsrWriteNdPort)
    }
    val tlbTransPorts = Vec(Param.Count.Tlb.transNum, new TlbTransPort)

    val difftest = if (isDiffTest) Some(Output(new DifftestTlbFillNdPort)) else None
  })

  // Connection graph:
  // +-----+ <---- maintenanceInfo <---- AddrTransStage
  // | TLB | <---> tlbTransPort    <---> Frontend translation
  // +-----+ <---> tlbTransPort    <---> AddrTransStage
  //         <---> csr             <---> Csr

  val tlbEntryVec = RegInit(VecInit(Seq.fill(Param.Count.Tlb.num)(TlbEntryBundle.default)))
  tlbEntryVec := tlbEntryVec
  val virtAddrLen = Width.Mem._addr
  val physAddrLen = Width.Mem._addr

  val isTlbRefillException = io.csr.in.estat.ecode === Csr.Estat.tlbr.ecode

  val csrOutReg = RegInit(TlbCsrWriteNdPort.default)
  csrOutReg := csrOutReg

  // Fallback
  io.csr.out := csrOutReg // CSR write information is given in next cycle
  io.tlbTransPorts.foreach { port =>
    port.exception.valid := false.B
    port.exception.bits  := DontCare
  }
  val isTlbMaintenance =
    io.maintenanceInfo.isSearch || io.maintenanceInfo.isWrite || io.maintenanceInfo.isFill || io.maintenanceInfo.isRead || io.maintenanceInfo.isInvalidate
  when(isTlbMaintenance) {
    csrOutReg.tlbidx.valid := false.B
    csrOutReg.asId.valid   := false.B
    csrOutReg.tlbehi.valid := false.B
    csrOutReg.tlbeloVec.foreach(_.valid := false.B)
    csrOutReg.tlbidx.bits := io.csr.in.tlbidx
    csrOutReg.asId.bits   := io.csr.in.asId
    csrOutReg.tlbehi.bits := io.csr.in.tlbehi
    csrOutReg.tlbeloVec.map(_.bits).zip(io.csr.in.tlbloVec).foreach {
      case (out, in) => out := in
    }
  }

  def isVirtPageNumMatched(compare: TlbCompareEntryBundle, vaddr: UInt) = Mux(
    compare.pageSize === Value.Tlb.Ps._4Kb,
    compare.virtPageNum(Width.Tlb._vppn - 1, 0) ===
      vaddr(virtAddrLen - 1, Value.Tlb.Ps._4Kb.litValue + 1),
    compare.virtPageNum(Width.Tlb._vppn - 10 - 1, 0) ===
      vaddr(virtAddrLen - 1, Value.Tlb.Ps._4Mb.litValue + 1)
  )

  io.tlbTransPorts.foreach { transPort =>
    // Lookup & Maintenance: Search
    val isFoundVec = VecInit(Seq.fill(Param.Count.Tlb.num)(false.B))
    tlbEntryVec.zip(isFoundVec).foreach {
      case (entry, isFound) =>
        val is4KbPageSize = entry.compare.pageSize === Value.Tlb.Ps._4Kb
        isFound := entry.compare.isExisted && (
          entry.compare.isGlobal || (entry.compare.asId === io.csr.in.asId.asid)
        ) && Mux(
          io.maintenanceInfo.isSearch,
          entry.compare.virtPageNum === io.csr.in.tlbehi.vppn,
          isVirtPageNumMatched(entry.compare, transPort.virtAddr)
        )
    }
    val selectedIndex = OHToUInt(isFoundVec)
    val selectedEntry = tlbEntryVec(selectedIndex)
    val selectedPage = Mux(
      transPort.virtAddr(selectedEntry.compare.pageSize(log2Ceil(virtAddrLen) - 1, 0)) === 0.U,
      selectedEntry.trans(0),
      selectedEntry.trans(1)
    )
    val isFound = isFoundVec.asUInt.orR
    when(io.maintenanceInfo.isSearch) {
      csrOutReg.tlbidx.valid := io.maintenanceInfo.isSearch
      csrOutReg.tlbidx.bits  := io.csr.in.tlbidx
      when(isFound) {
        csrOutReg.tlbidx.bits.index := selectedIndex
        csrOutReg.tlbidx.bits.ne    := false.B
      }.otherwise {
        csrOutReg.tlbidx.bits.ne := true.B
      }
    }

    // Translate
    transPort.physAddr := Mux(
      selectedEntry.compare.pageSize === Value.Tlb.Ps._4Kb,
      Cat(
        selectedPage.physPageNum(Width.Tlb._ppn - 1, 0),
        transPort.virtAddr(Value.Tlb.Ps._4Kb.litValue - 1, 0)
      ),
      Cat(
        selectedPage.physPageNum(Width.Tlb._ppn - 10 - 1, 0),
        transPort.virtAddr(Value.Tlb.Ps._4Mb.litValue - 1, 0)
      )
    )

    // Handle exception
    when(!isFound) {
      transPort.exception.valid := true.B
      transPort.exception.bits  := Csr.ExceptionIndex.tlbr
    }
    when(!selectedPage.isValid) {
      switch(transPort.memType) {
        is(TlbMemType.load) {
          transPort.exception.valid := true.B
          transPort.exception.bits  := Csr.ExceptionIndex.pil
        }
        is(TlbMemType.store) {
          transPort.exception.valid := true.B
          transPort.exception.bits  := Csr.ExceptionIndex.pis
        }
        is(TlbMemType.fetch) {
          transPort.exception.valid := true.B
          transPort.exception.bits  := Csr.ExceptionIndex.pif
        }
      }
    }.elsewhen(selectedPage.plv < io.csr.in.plv) {
      transPort.exception.valid := true.B
      transPort.exception.bits  := Csr.ExceptionIndex.ppi
    }.elsewhen(!selectedPage.isDirty && transPort.memType === TlbMemType.store) {
      transPort.exception.valid := true.B
      transPort.exception.bits  := Csr.ExceptionIndex.pme
    }
  }

  // Maintenance: Read
  val readEntry = tlbEntryVec(io.csr.in.tlbidx.index)
  when(io.maintenanceInfo.isRead) {
    csrOutReg.tlbidx.valid := true.B
    csrOutReg.tlbehi.valid := true.B
    csrOutReg.asId.valid   := true.B
    csrOutReg.tlbeloVec.foreach(_.valid := true.B)
    csrOutReg.asId.bits := io.csr.in.asId
    when(readEntry.compare.isExisted) {
      csrOutReg.tlbidx.bits.ne   := false.B
      csrOutReg.tlbidx.bits.ps   := readEntry.compare.pageSize
      csrOutReg.asId.bits.asid   := readEntry.compare.asId
      csrOutReg.tlbehi.bits.vppn := readEntry.compare.virtPageNum
      csrOutReg.tlbeloVec.map(_.bits).zip(readEntry.trans).foreach {
        case (tlbelo, trans) =>
          tlbelo.v   := trans.isValid
          tlbelo.d   := trans.isDirty
          tlbelo.plv := trans.plv
          tlbelo.mat := trans.mat
          tlbelo.g   := readEntry.compare.isGlobal
          tlbelo.ppn := trans.physPageNum
      }
    }.otherwise {
      csrOutReg.tlbidx.bits.ne := true.B
      csrOutReg.tlbidx.bits.ps := 0.U
      csrOutReg.asId.bits      := 0.U.asTypeOf(new AsidBundle)
      csrOutReg.tlbehi.bits    := 0.U.asTypeOf(new TlbehiBundle)
      csrOutReg.tlbeloVec.foreach(_.bits := 0.U.asTypeOf(new TlbeloBundle))
    }
  }

  // Maintenance: Write & Fill
  val fillIndex = PriorityEncoder(tlbEntryVec.map(!_.compare.isExisted))
  val writeEntry = tlbEntryVec(
    Mux(
      io.maintenanceInfo.isWrite,
      io.csr.in.tlbidx.index,
      fillIndex
    )
  )
  when(io.maintenanceInfo.isWrite || io.maintenanceInfo.isFill) {
    writeEntry.compare.isExisted := !io.csr.in.tlbidx.ne || isTlbRefillException

    writeEntry.compare.pageSize    := io.csr.in.tlbidx.ps
    writeEntry.compare.virtPageNum := io.csr.in.tlbehi.vppn
    writeEntry.compare.isGlobal    := io.csr.in.tlbloVec.map(_.g).reduce(_ || _)
    writeEntry.compare.asId        := io.csr.in.asId.asid
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

  io.difftest match {
    case Some(dt) =>
      dt.valid     := io.maintenanceInfo.isFill
      dt.fillIndex := fillIndex
    case None =>
  }

  // Maintenance: Invalidate
  def invalidateEntry(entry: TlbEntryBundle): Unit = {
    entry.compare.isExisted := false.B
    entry.trans.foreach(_.isValid := false.B)
  }
  def isAsIdMatched(entry: TlbEntryBundle) = entry.compare.asId === io.maintenanceInfo.registerAsid

  when(io.maintenanceInfo.isInvalidate) {
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
              isVirtPageNumMatched(entry.compare, io.maintenanceInfo.virtAddr)
          ) {
            invalidateEntry(entry)
          }
        }
      }
      is(clrGloblAsIdVa) {
        tlbEntryVec.foreach { entry =>
          when(
            (entry.compare.isGlobal ||
              isAsIdMatched(entry)) &&
              isVirtPageNumMatched(entry.compare, io.maintenanceInfo.virtAddr)
          ) {
            invalidateEntry(entry)
          }
        }
      }
    }
  }
}
