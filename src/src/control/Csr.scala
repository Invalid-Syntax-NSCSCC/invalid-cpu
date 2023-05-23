package control

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._
import spec.PipelineStageIndex
import control.bundles._
import common.bundles.RfReadPort
import control.csrRegsBundles._
import spec.Param.isDiffTest

// TODO: 中断：ecfg, estat.is
// TODO: 同时读写csrRegs时候读端口的赋值
// TODO: TLB相关寄存器
// TODO: 计时器中断
class Csr(
  writeNum: Int = Param.csrRegsWriteNum,
  readNum:  Int = Param.csrRegsReadNum)
    extends Module {
  val io = IO(new Bundle {
    // `Cu` -> `Csr`
    val writePorts = Input(Vec(writeNum, new CsrWriteNdPort))
    val csrMessage = Input(new CuToCsrNdPort)
    val csrValues  = Output(new CsrValuePort)
    // `Csr` <-> `IssueStage` / `RegReadStage` ???
    val readPorts = Vec(Param.csrRegsReadNum, new CsrReadPort)

    //   val difftest = if (isDiffTest) Some(Output(new Bundle {
    //     val crmd      = UInt(32.W)
    //     val prmd      = UInt(32.W)
    //     val ectl      = UInt(32.W)
    //     val estat     = new EstatBundle
    //     val era       = UInt(32.W)
    //     val badv      = UInt(32.W)
    //     val eentry    = UInt(32.W)
    //     val tlbidx    = UInt(32.W)
    //     val tlbehi    = UInt(32.W)
    //     val tlbelo0   = UInt(32.W)
    //     val tlbelo1   = UInt(32.W)
    //     val asid      = UInt(32.W)
    //     val save0     = UInt(32.W)
    //     val save1     = UInt(32.W)
    //     val save2     = UInt(32.W)
    //     val save3     = UInt(32.W)
    //     val tid       = UInt(32.W)
    //     val tcfg      = UInt(32.W)
    //     val tval      = UInt(32.W)
    //     val ticlr     = UInt(32.W)
    //     val llbctl    = UInt(32.W)
    //     val tlbrentry = UInt(32.W)
    //     val dmw0      = UInt(32.W)
    //     val dmw1      = UInt(32.W)
    //     val pgdl      = UInt(32.W)
    //     val pgdh      = UInt(32.W)
    //   }))
    //   else None
  })

  // Util: view UInt as Bundle
  class BundlePassPort[T <: Bundle](port: T) extends Bundle {
    val in  = Wire(port)
    val out = Wire(port)
  }

  def viewUInt[T <: Bundle](u: UInt, bun: T): BundlePassPort[T] = {
    val passPort = new BundlePassPort(bun)
    u            := passPort.in.asUInt
    passPort.in  := u.asTypeOf(bun)
    passPort.out := u.asTypeOf(bun)
    passPort
  }

  // 定时器中断
  val timeInterrupt = RegInit(false.B)

  // val csrRegs = RegInit(VecInit(Seq.fill(Count.csrReg)(zeroWord)))
  val csrValuesReg = RegInit(CsrValuePort.default)

  // read
  // io.readPorts.foreach { readPort =>
  //   readPort.data := Mux(
  //     readPort.en,
  //     csrRegs(readPort.addr),
  //     zeroWord
  //   )
  // }
  io.readPorts.foreach { readPort =>
    readPort.data := zeroWord
    when(readPort.en) {
      switch(readPort.addr) {
        is(spec.Csr.Index.crmd) {
          readPort.data := csrValuesReg.crmd.asUInt
        }
        is(spec.Csr.Index.prmd) {
          readPort.data := csrValuesReg.prmd.asUInt
        }
        is(spec.Csr.Index.euen) {
          readPort.data := csrValuesReg.euen.asUInt
        }
        is(spec.Csr.Index.ecfg) {
          readPort.data := csrValuesReg.ecfg.asUInt
        }
        is(spec.Csr.Index.estat) {
          readPort.data := csrValuesReg.estat.asUInt
        }
        is(spec.Csr.Index.era) {
          readPort.data := csrValuesReg.era.asUInt
        }
        is(spec.Csr.Index.badv) {
          readPort.data := csrValuesReg.badv.asUInt
        }
        is(spec.Csr.Index.eentry) {
          readPort.data := csrValuesReg.eentry.asUInt
        }
        is(spec.Csr.Index.tlbidx) {
          readPort.data := csrValuesReg.tlbidx.asUInt
        }
        is(spec.Csr.Index.tlbehi) {
          readPort.data := csrValuesReg.tlbehi.asUInt
        }
        is(spec.Csr.Index.tlbelo0) {
          readPort.data := csrValuesReg.tlbelo0.asUInt
        }
        is(spec.Csr.Index.tlbelo1) {
          readPort.data := csrValuesReg.tlbelo1.asUInt
        }
        is(spec.Csr.Index.asid) {
          readPort.data := csrValuesReg.asid.asUInt
        }
        is(spec.Csr.Index.pgdh) {
          readPort.data := csrValuesReg.pgdh.asUInt
        }
        is(spec.Csr.Index.pgdl) {
          readPort.data := csrValuesReg.pgdl.asUInt
        }
        is(spec.Csr.Index.pgd) {
          readPort.data := csrValuesReg.pgd.asUInt
        }
        is(spec.Csr.Index.cpuid) {
          readPort.data := csrValuesReg.cpuid.asUInt
        }
        is(spec.Csr.Index.save0) {
          readPort.data := csrValuesReg.save0.asUInt
        }
        is(spec.Csr.Index.save1) {
          readPort.data := csrValuesReg.save1.asUInt
        }
        is(spec.Csr.Index.save2) {
          readPort.data := csrValuesReg.save2.asUInt
        }
        is(spec.Csr.Index.save3) {
          readPort.data := csrValuesReg.save3.asUInt
        }
        is(spec.Csr.Index.tid) {
          readPort.data := csrValuesReg.tid.asUInt
        }
        is(spec.Csr.Index.tcfg) {
          readPort.data := csrValuesReg.tcfg.asUInt
        }
        is(spec.Csr.Index.tval) {
          readPort.data := csrValuesReg.tval.asUInt
        }
        is(spec.Csr.Index.ticlr) {
          readPort.data := csrValuesReg.ticlr.asUInt
        }
        is(spec.Csr.Index.llbctl) {
          readPort.data := csrValuesReg.llbctl.asUInt
        }
        is(spec.Csr.Index.tlbrentry) {
          readPort.data := csrValuesReg.tlbrentry.asUInt
        }
        is(spec.Csr.Index.dmw0) {
          readPort.data := csrValuesReg.dmw0.asUInt
        }
        is(spec.Csr.Index.dmw1) {
          readPort.data := csrValuesReg.dmw1.asUInt
        }
      }
    }
  }

  // 输出
  io.csrValues := csrValuesReg

  // 软件写csrRegs
  // 保留域断断续续的样子真是可爱捏
  io.writePorts.foreach { writePort =>
    when(writePort.en) {
      // 保留域
      switch(writePort.addr) {
        is(spec.Csr.Index.crmd) {
          csrValuesReg.crmd := Cat(0.U(23.W), writePort.data(8, 0)).asTypeOf(csrValuesReg.crmd)
        }
        is(spec.Csr.Index.prmd) {
          csrValuesReg.prmd := Cat(0.U(29.W), writePort.data(2, 0)).asTypeOf(csrValuesReg.prmd)
        }
        is(spec.Csr.Index.euen) {
          csrValuesReg.euen := Cat(0.U(31.W), writePort.data(0)).asTypeOf(csrValuesReg.euen)
        }
        is(spec.Csr.Index.ecfg) {
          csrValuesReg.ecfg := Cat(0.U(19.W), writePort.data(12, 0)).asTypeOf(csrValuesReg.ecfg)
        }
        is(spec.Csr.Index.estat) {
          csrValuesReg.estat := Cat(false.B, writePort.data(30, 16), 0.U(3.W), writePort.data(12, 0))
            .asTypeOf(csrValuesReg.estat)
        }
        is(spec.Csr.Index.era) {
          csrValuesReg.era := writePort.data.asTypeOf(csrValuesReg.era)
        }
        is(spec.Csr.Index.badv) {
          csrValuesReg.badv := writePort.data.asTypeOf(csrValuesReg.badv)
        }
        is(spec.Csr.Index.save0) {
          csrValuesReg.save0 := writePort.data.asTypeOf(new CsrSaveBundle)
        }
        is(spec.Csr.Index.save1) {
          csrValuesReg.save1 := writePort.data.asTypeOf(new CsrSaveBundle)
        }
        is(spec.Csr.Index.save2) {
          csrValuesReg.save2 := writePort.data.asTypeOf(new CsrSaveBundle)
        }
        is(spec.Csr.Index.save3) {
          csrValuesReg.save3 := writePort.data.asTypeOf(new CsrSaveBundle)
        }
        is(spec.Csr.Index.tid) {
          csrValuesReg.tid := writePort.data.asTypeOf(csrValuesReg.tid)
        }
        is(spec.Csr.Index.eentry) {
          csrValuesReg.eentry := Cat(writePort.data(31, 6), 0.U(6.W)).asTypeOf(csrValuesReg.eentry)
        }
        is(spec.Csr.Index.cpuid) {
          csrValuesReg.cpuid := Cat(0.U(23.W), writePort.data(8, 0)).asTypeOf(csrValuesReg.cpuid)
        }
        is(spec.Csr.Index.llbctl) {
          csrValuesReg.llbctl := Cat(
            0.U(29.W),
            writePort.data(2),
            false.B,
            Mux( // 软件向wcllb写1时清零llbit，写0时忽略
              writePort.data(1),
              false.B,
              // csrRegs(spec.Csr.Index.llbctl(1))
              csrValuesReg.llbctl.rollb
            )
          ).asTypeOf(csrValuesReg.llbctl)
        }
        is(spec.Csr.Index.tlbidx) {
          csrValuesReg.tlbidx := Cat(
            writePort.data(31),
            false.B,
            writePort.data(29, 24),
            0.U((24 - spec.Csr.Tlbidx.Width.index).W),
            writePort.data(spec.Csr.Tlbidx.Width.index - 1, 0)
          ).asTypeOf(csrValuesReg.tlbidx)
        }
        is(spec.Csr.Index.tlbehi) {
          csrValuesReg.tlbehi := Cat(writePort.data(31, 13), 0.U(13.W)).asTypeOf(csrValuesReg.tlbehi)
        }
        is(spec.Csr.Index.tlbelo0) {
          csrValuesReg.tlbelo0 := Cat(
            writePort.data(31, 8),
            false.B,
            writePort.data(6, 0)
          ).asTypeOf(new TlbeloBundle)
        }
        is(spec.Csr.Index.tlbelo1) {
          csrValuesReg.tlbelo1 := Cat(
            writePort.data(31, 8),
            false.B,
            writePort.data(6, 0)
          ).asTypeOf(new TlbeloBundle)
        }
        is(spec.Csr.Index.asid) {
          csrValuesReg.asid := Cat(
            0.U(8.W),
            writePort.data(23, 16),
            0.U(6.W),
            writePort.data(9, 0)
          ).asTypeOf(csrValuesReg.asid)
        }
        is(spec.Csr.Index.pgd) {
          csrValuesReg.pgd := Cat(writePort.data(31, 12), 0.U(12.W)).asTypeOf(csrValuesReg.pgd)
        }
        is(spec.Csr.Index.pgdl) {
          csrValuesReg.pgdl := Cat(writePort.data(31, 12), 0.U(12.W)).asTypeOf(csrValuesReg.pgdl)
        }
        is(spec.Csr.Index.pgdh) {
          csrValuesReg.pgdh := Cat(writePort.data(31, 12), 0.U(12.W)).asTypeOf(csrValuesReg.pgdh)
        }
        is(spec.Csr.Index.tlbrentry) {
          csrValuesReg.tlbrentry := Cat(writePort.data(31, 6), 0.U(6.W)).asTypeOf(csrValuesReg.tlbrentry)
        }
        is(spec.Csr.Index.dmw0) {
          csrValuesReg.dmw0 := Cat(
            writePort.data(31, 29),
            false.B,
            writePort.data(27, 25),
            0.U(19.W),
            writePort.data(5, 3),
            0.U(2.W),
            writePort.data(0)
          ).asTypeOf(new DmwBundle)
        }
        is(spec.Csr.Index.dmw1) {
          csrValuesReg.dmw1 := Cat(
            writePort.data(31, 29),
            false.B,
            writePort.data(27, 25),
            0.U(19.W),
            writePort.data(5, 3),
            0.U(2.W),
            writePort.data(0)
          ).asTypeOf(new DmwBundle)
        }
        is(spec.Csr.Index.tcfg) {
          csrValuesReg.tcfg := Cat(
            0.U((32 - spec.Csr.TimeVal.Width.timeVal).W),
            writePort.data(spec.Csr.TimeVal.Width.timeVal - 1, 0)
          ).asTypeOf(csrValuesReg.tcfg)
        }
        is(spec.Csr.Index.tval) {
          csrValuesReg.tval := Cat(
            0.U((32 - spec.Csr.TimeVal.Width.timeVal).W),
            writePort.data(spec.Csr.TimeVal.Width.timeVal - 1, 0)
          ).asTypeOf(csrValuesReg.tval)
        }
        is(spec.Csr.Index.ticlr) {
          csrValuesReg.ticlr := Cat(
            0.U(31.W),
            false.B
          ).asTypeOf(csrValuesReg.ticlr)
          when(writePort.data(0) === true.B) {
            timeInterrupt := false.B
          }
        }
      }
    }
  }

  /** CRMD 当前模式信息
    */
  // 普通例外
  when(io.csrMessage.exceptionFlush) {
    csrValuesReg.crmd.plv := 0.U
    csrValuesReg.crmd.ie  := 0.U
  }

  // tlb重填例外
  when(io.csrMessage.tlbRefillException) {
    csrValuesReg.crmd.da := true.B
    csrValuesReg.crmd.pg := false.B
  }

  // 从例外处理程序返回
  when(io.csrMessage.ertnFlush) {
    csrValuesReg.crmd.plv := csrValuesReg.prmd.pplv
    csrValuesReg.crmd.ie  := csrValuesReg.prmd.pie
    when(csrValuesReg.estat.ecode === spec.Csr.Estat.tlbr.ecode) {
      csrValuesReg.crmd.da := false.B
      csrValuesReg.crmd.pg := true.B
    }
  }

  /** PRMD 例外前模式信息
    */
  when(io.csrMessage.exceptionFlush) {
    csrValuesReg.prmd.pplv := csrValuesReg.crmd.plv
    csrValuesReg.prmd.pie  := csrValuesReg.crmd.ie
  }

  // estat
  when(io.csrMessage.exceptionFlush) {
    csrValuesReg.estat.ecode    := io.csrMessage.ecodeBunle.ecode
    csrValuesReg.estat.esubcode := io.csrMessage.ecodeBunle.esubcode
  }

  // era
  when(io.csrMessage.exceptionFlush) {
    csrValuesReg.era.pc := io.csrMessage.era
  }

  // BADV 出错虚地址
  when(io.csrMessage.badVAddrSet.en) {
    csrValuesReg.badv.vaddr := io.csrMessage.badVAddrSet.addr
  }

  // LLBit Control
  when(io.csrMessage.llbitSet.en) {
    csrValuesReg.llbctl.rollb := io.csrMessage.llbitSet.setValue
  }
  when(io.csrMessage.ertnFlush) {
    csrValuesReg.llbctl.klo := false.B
  }

  // TimeVal

  when(csrValuesReg.tval.timeVal.orR) { // 定时器不为0
    when(csrValuesReg.tcfg.en) {
      csrValuesReg.tval.timeVal := csrValuesReg.tval.timeVal - 1.U
    }
  }.otherwise { // 减到0
    when(csrValuesReg.tcfg.periodic) {
      timeInterrupt             := true.B
      csrValuesReg.tval.timeVal := Cat(csrValuesReg.tcfg.initVal, 0.U(2.W))
    } // 为0时停止计数
  }

  // Difftest
  // io.difftest match {
  //   case Some(dt) =>
  //     dt.crmd := crmd.out.asUInt
  //     dt.prmd := prmd.out.asUInt
  //     // TODO: `ectl` is not implemented
  //     dt.ectl      := DontCare
  //     dt.estat     := estat.out
  //     dt.era       := era.out.asUInt
  //     dt.badv      := badv.out.asUInt
  //     dt.eentry    := eentry.out.asUInt
  //     dt.tlbidx    := tlbidx.out.asUInt
  //     dt.tlbehi    := tlbehi.out.asUInt
  //     dt.tlbelo0   := tlbelo0.out.asUInt
  //     dt.tlbelo1   := tlbelo1.out.asUInt
  //     dt.asid      := asid.out.asUInt
  //     dt.save0     := saves(0).out.asUInt
  //     dt.save1     := saves(1).out.asUInt
  //     dt.save2     := saves(2).out.asUInt
  //     dt.save3     := saves(3).out.asUInt
  //     dt.tid       := tid.out.asUInt
  //     dt.tcfg      := tcfg.out.asUInt
  //     dt.tval      := tval.out.asUInt
  //     dt.ticlr     := ticlr.out.asUInt
  //     dt.llbctl    := llbctl.out.asUInt
  //     dt.tlbrentry := tlbrentry.out.asUInt
  //     dt.dmw0      := dmw0.out.asUInt
  //     dt.dmw1      := dmw1.out.asUInt
  //     dt.pgdl      := pgdl.out.asUInt
  //     dt.pgdh      := pgdh.out.asUInt
  //   case _ =>
  // }
}
