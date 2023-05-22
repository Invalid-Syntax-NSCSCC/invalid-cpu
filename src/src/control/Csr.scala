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
    // `Csr` -> `Cu` // TODO: Change this comment
    val csrValues = Output(new CsrToCuNdPort)
    // `Csr` <-> `IssueStage` / `RegReadStage` ???
    val readPorts = Vec(Param.csrRegsReadNum, new CsrReadPort)

    val difftest = if (isDiffTest) Some(Output(new Bundle {
      val crmd      = UInt(32.W)
      val prmd      = UInt(32.W)
      val ectl      = UInt(32.W)
      val estat     = new EstatBundle
      val era       = UInt(32.W)
      val badv      = UInt(32.W)
      val eentry    = UInt(32.W)
      val tlbidx    = UInt(32.W)
      val tlbehi    = UInt(32.W)
      val tlbelo0   = UInt(32.W)
      val tlbelo1   = UInt(32.W)
      val asid      = UInt(32.W)
      val save0     = UInt(32.W)
      val save1     = UInt(32.W)
      val save2     = UInt(32.W)
      val save3     = UInt(32.W)
      val tid       = UInt(32.W)
      val tcfg      = UInt(32.W)
      val tval      = UInt(32.W)
      val ticlr     = UInt(32.W)
      val llbctl    = UInt(32.W)
      val tlbrentry = UInt(32.W)
      val dmw0      = UInt(32.W)
      val dmw1      = UInt(32.W)
      val pgdl      = UInt(32.W)
      val pgdh      = UInt(32.W)
    }))
    else None
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

  val csrRegs = RegInit(VecInit(Seq.fill(Count.csrReg)(zeroWord)))

  // read
  io.readPorts.foreach { readPort =>
    readPort.data := Mux(
      readPort.en,
      csrRegs(readPort.addr),
      zeroWord
    )
  }

  // 输出
  io.csrValues.era       := csrRegs(spec.Csr.Index.era)
  io.csrValues.eentry    := csrRegs(spec.Csr.Index.eentry)
  io.csrValues.tlbrentry := csrRegs(spec.Csr.Index.tlbrentry)

  // 软件写csrRegs
  // 保留域断断续续的样子真是可爱捏
  io.writePorts.foreach { writePort =>
    when(writePort.en) {
      csrRegs(writePort.addr) := writePort.data
      // 保留域
      switch(writePort.addr) {
        is(spec.Csr.Index.crmd) {
          csrRegs(writePort.addr) := Cat(0.U(23.W), writePort.data(8, 0))
        }
        is(spec.Csr.Index.prmd) {
          csrRegs(writePort.addr) := Cat(0.U(29.W), writePort.data(2, 0))
        }
        is(spec.Csr.Index.euen) {
          csrRegs(writePort.addr) := Cat(0.U(31.W), writePort.data(0))
        }
        is(spec.Csr.Index.ecfg) {
          csrRegs(writePort.addr) := Cat(0.U(19.W), writePort.data(12, 0))
        }
        is(spec.Csr.Index.estat) {
          csrRegs(writePort.addr) := Cat(false.B, writePort.data(30, 16), 0.U(3.W), writePort.data(12, 0))
        }
        is(
          spec.Csr.Index.era,
          spec.Csr.Index.badv,
          spec.Csr.Index.save0,
          spec.Csr.Index.save1,
          spec.Csr.Index.save2,
          spec.Csr.Index.save3,
          spec.Csr.Index.tid
        ) {
          csrRegs(writePort.addr) := writePort.data
        }
        is(spec.Csr.Index.eentry) {
          csrRegs(writePort.addr) := Cat(writePort.data(31, 6), 0.U(6.W))
        }
        is(spec.Csr.Index.cpuid) {
          csrRegs(writePort.addr) := Cat(0.U(23.W), writePort.data(8, 0))
        }
        is(spec.Csr.Index.llbctl) {
          csrRegs(writePort.addr) := Cat(
            0.U(29.W),
            writePort.data(2),
            false.B,
            Mux( // 软件向wcllb写1时清零llbit，写0时忽略
              writePort.data(1),
              false.B,
              csrRegs(spec.Csr.Index.llbctl(1))
            )
          )
        }
        is(spec.Csr.Index.tlbidx) {
          csrRegs(writePort.addr) := Cat(
            writePort.data(31),
            false.B,
            writePort.data(29, 24),
            0.U((24 - spec.Csr.Tlbidx.Width.index).W),
            writePort.data(spec.Csr.Tlbidx.Width.index - 1, 0)
          )
        }
        is(spec.Csr.Index.tlbehi) {
          csrRegs(writePort.addr) := Cat(writePort.data(31, 13), 0.U(13.W))
        }
        is(spec.Csr.Index.tlbelo0, spec.Csr.Index.tlbelo1) {
          csrRegs(writePort.addr) := Cat(
            writePort.data(31, 8),
            false.B,
            writePort.data(6, 0)
          )
        }
        is(spec.Csr.Index.asid) {
          csrRegs(writePort.addr) := Cat(
            0.U(8.W),
            writePort.data(23, 16),
            0.U(6.W),
            writePort.data(9, 0)
          )
        }
        is(spec.Csr.Index.pgdl, spec.Csr.Index.pgdh, spec.Csr.Index.pgd) {
          csrRegs(writePort.addr) := Cat(writePort.data(31, 12), 0.U(12.W))
        }
        is(spec.Csr.Index.tlbrentry) {
          csrRegs(writePort.addr) := Cat(writePort.data(31, 6), 0.U(6.W))
        }
        is(spec.Csr.Index.dmw0, spec.Csr.Index.dmw1) {
          csrRegs(writePort.addr) := Cat(
            writePort.data(31, 29),
            false.B,
            writePort.data(27, 25),
            0.U(19.W),
            writePort.data(5, 3),
            0.U(2.W),
            writePort.data(0)
          )
        }
        is(spec.Csr.Index.tcfg, spec.Csr.Index.tval) {
          csrRegs(writePort.addr) := Cat(
            0.U((32 - spec.Csr.TimeVal.Width.timeVal).W),
            writePort.data(spec.Csr.TimeVal.Width.timeVal - 1, 0)
          )
        }
        is(spec.Csr.Index.ticlr) {
          csrRegs(writePort.addr) := Cat(
            0.U(31.W),
            false.B
          )
          when(writePort.data(0) === true.B) {
            timeInterrupt := false.B
          }
        }
      }
    }
  }

  // CRMD 当前模式信息

  val crmd = viewUInt(csrRegs(spec.Csr.Index.crmd), new CrmdBundle)

  // PRMD 例外前模式信息
  val prmd = viewUInt(csrRegs(spec.Csr.Index.prmd), new PrmdBundle)

  // EUEN扩展部件使能
  val euen = viewUInt(csrRegs(spec.Csr.Index.euen), new EuenBundle)

  // ECFG 例外控制
  val ecfg = viewUInt(csrRegs(spec.Csr.Index.ecfg), new EcfgBundle)

  // ESTAT
  val estat = viewUInt(csrRegs(spec.Csr.Index.estat), new EstatBundle)

  // ERA 例外返回地址: 触发例外指令的pc记录在此
  val era = viewUInt(csrRegs(spec.Csr.Index.era), new EraBundle)
  era.in := EraBundle.default

  // BADV 出错虚地址
  val badv = viewUInt(csrRegs(spec.Csr.Index.badv), new BadvBundle)

  // EENTRY 例外入口地址
  val eentry = viewUInt(csrRegs(spec.Csr.Index.eentry), new EentryBundle)

  // CPUID 处理器编号
  val cpuid = viewUInt(csrRegs(spec.Csr.Index.cpuid), new CpuidBundle)

  // SAVE0-3 数据保存
  val saves = VecInit(spec.Csr.Index.save0, spec.Csr.Index.save1, spec.Csr.Index.save2, spec.Csr.Index.save3).map {
    idx =>
      viewUInt(csrRegs(idx), new CsrSaveBundle)
  }

  // LLBCTL  LLBit控制
  val llbctl = viewUInt(csrRegs(spec.Csr.Index.llbctl), new LlbctlBundle)

  // TLBIDX  TLB索引
  val tlbidx = viewUInt(csrRegs(spec.Csr.Index.tlbidx), new TlbidxBundle)

  // TLBEHI  TLB表项高位
  val tlbehi = viewUInt(csrRegs(spec.Csr.Index.tlbehi), new TlbehiBundle)

  // TLBELO 0-1  TLB表项低位
  val tlbelo0 = viewUInt(csrRegs(spec.Csr.Index.tlbelo0), new TlbeloBundle)

  val tlbelo1 = viewUInt(csrRegs(spec.Csr.Index.tlbelo1), new TlbeloBundle)

  // ASID 地址空间标识符
  val asid = viewUInt(csrRegs(spec.Csr.Index.asid), new AsidBundle)

  // PGDL 低半地址空间全局目录基址
  val pgdl = viewUInt(csrRegs(spec.Csr.Index.pgdl), new PgdlBundle)

  // PGDH 高半地址空间全局目录基址
  val pgdh = viewUInt(csrRegs(spec.Csr.Index.pgdh), new PgdhBundle)

  // PGD 全局地址空间全局目录基址
  val pgd = viewUInt(csrRegs(spec.Csr.Index.pgd), new PgdBundle)

  // TLBRENTRY  TLB重填例外入口地址
  val tlbrentry = viewUInt(csrRegs(spec.Csr.Index.tlbrentry), new TlbrentryBundle)

  // DMW 0-1 直接映射配置窗口
  val dmw0 = viewUInt(csrRegs(spec.Csr.Index.dmw0), new DmwBundle)

  val dmw1 = viewUInt(csrRegs(spec.Csr.Index.dmw1), new DmwBundle)

  // TID 定时器编号
  val tid = viewUInt(csrRegs(spec.Csr.Index.tid), new TidBundle)

  // TCFG 定时器配置
  val tcfg = viewUInt(csrRegs(spec.Csr.Index.tcfg), new TcfgBundle)

  // TVAL 定时器数值
  val tval = viewUInt(csrRegs(spec.Csr.Index.tval), new TvalBundle)

  // TICLR 定时器中断清除
  val ticlr = viewUInt(csrRegs(spec.Csr.Index.ticlr), new TiclrBundle)

  /** CRMD 当前模式信息
    */
  // 普通例外
  when(io.csrMessage.exceptionFlush) {
    crmd.in.plv := 0.U
    crmd.in.ie  := 0.U
  }

  // tlb重填例外
  when(io.csrMessage.tlbRefillException) {
    crmd.in.da := true.B
    crmd.in.pg := false.B
  }

  // 从例外处理程序返回
  when(io.csrMessage.ertnFlush) {
    crmd.in.plv := prmd.out.pplv
    crmd.in.ie  := prmd.out.pie
    when(estat.out.ecode === spec.Csr.Estat.tlbr.ecode) {
      crmd.in.da := false.B
      crmd.in.pg := true.B
    }
  }

  /** PRMD 例外前模式信息
    */
  when(io.csrMessage.exceptionFlush) {
    prmd.in.pplv := crmd.out.plv
    prmd.in.pie  := crmd.out.ie
  }

  // estat
  when(io.csrMessage.exceptionFlush) {
    estat.in.ecode    := io.csrMessage.ecodeBunle.ecode
    estat.in.esubcode := io.csrMessage.ecodeBunle.esubcode
  }

  // era
  when(io.csrMessage.exceptionFlush) {
    era.in.pc := io.csrMessage.era
  }

  // BADV 出错虚地址
  when(io.csrMessage.badVAddrSet.en) {
    badv.in.vaddr := io.csrMessage.badVAddrSet.addr
  }

  // LLBit Control
  when(io.csrMessage.llbitSet.en) {
    llbctl.in.rollb := io.csrMessage.llbitSet.setValue
  }
  when(io.csrMessage.ertnFlush) {
    llbctl.in.klo := false.B
  }

  // TimeVal

  when(tval.out.timeVal.orR) { // 定时器不为0
    when(tcfg.out.en) {
      tval.in.timeVal := tval.out.timeVal - 1.U
    }
  }.otherwise { // 减到0
    when(tcfg.out.periodic) {
      timeInterrupt   := true.B
      tval.in.timeVal := Cat(tcfg.out.initVal, 0.U(2.W))
    } // 为0时停止计数
  }

  // Difftest
  io.difftest match {
    case Some(dt) =>
      dt.crmd := crmd.out.asUInt
      dt.prmd := prmd.out.asUInt
      // TODO: `ectl` is not implemented
      dt.ectl      := DontCare
      dt.estat     := estat.out
      dt.era       := era.out.asUInt
      dt.badv      := badv.out.asUInt
      dt.eentry    := eentry.out.asUInt
      dt.tlbidx    := tlbidx.out.asUInt
      dt.tlbehi    := tlbehi.out.asUInt
      dt.tlbelo0   := tlbelo0.out.asUInt
      dt.tlbelo1   := tlbelo1.out.asUInt
      dt.asid      := asid.out.asUInt
      dt.save0     := saves(0).out.asUInt
      dt.save1     := saves(1).out.asUInt
      dt.save2     := saves(2).out.asUInt
      dt.save3     := saves(3).out.asUInt
      dt.tid       := tid.out.asUInt
      dt.tcfg      := tcfg.out.asUInt
      dt.tval      := tval.out.asUInt
      dt.ticlr     := ticlr.out.asUInt
      dt.llbctl    := llbctl.out.asUInt
      dt.tlbrentry := tlbrentry.out.asUInt
      dt.dmw0      := dmw0.out.asUInt
      dt.dmw1      := dmw1.out.asUInt
      dt.pgdl      := pgdl.out.asUInt
      dt.pgdh      := pgdh.out.asUInt
    case _ =>
  }
}
