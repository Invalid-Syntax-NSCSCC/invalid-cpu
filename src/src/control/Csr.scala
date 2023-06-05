package control

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
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
    // `Csr` <-> `RegReadStage`
    val readPorts = Vec(Param.csrRegsReadNum, new CsrReadPort)
    // `Csr` -> `WbStage`
    val hasInterrupt = Output(Bool())
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

  val csrRegs = RegInit(VecInit(Seq.fill(Count.csrReg)(zeroWord)))

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

  // read
  io.readPorts.foreach { readPort =>
    readPort.data := zeroWord
    when(readPort.en) {
      readPort.data := csrRegs(readPort.addr)
      when(readPort.addr === spec.Csr.Index.pgd) {
        readPort.data := Mux(
          badv.out.vaddr(31),
          csrRegs(spec.Csr.Index.pgdh),
          csrRegs(spec.Csr.Index.pgdl)
        )
      }
    }
  }

  // TimeVal

  when(tcfg.out.en) {
    when(tval.out.timeVal === 0.U) {
      tval.in.timeVal := Mux(
        tcfg.out.periodic,
        Cat(tcfg.out.initVal, 0.U(2.W)),
        "hffffffff".U(32.W)
      )
      estat.in.is_timeInt := true.B
    }.otherwise {
      tval.in.timeVal := tval.out.timeVal - 1.U
    }
  }

  // 软件写csrRegs
  // 保留域断断续续的样子真是可爱捏
  io.writePorts.foreach { writePort =>
    when(writePort.en) {
      // 保留域
      switch(writePort.addr) {
        is(spec.Csr.Index.crmd) {
          crmd.in.plv  := writePort.data(1, 0)
          crmd.in.ie   := writePort.data(2)
          crmd.in.da   := writePort.data(3)
          crmd.in.pg   := writePort.data(4)
          crmd.in.datf := writePort.data(6, 5)
          crmd.in.datm := writePort.data(8, 7)
        }
        is(spec.Csr.Index.prmd) {
          prmd.in := Cat(0.U(29.W), writePort.data(2, 0)).asTypeOf(prmd.in)
        }
        is(spec.Csr.Index.euen) {
          euen.in := Cat(0.U(31.W), writePort.data(0)).asTypeOf(euen.in)
        }
        is(spec.Csr.Index.ecfg) {
          ecfg.in := Cat(0.U(19.W), writePort.data(12, 0)).asTypeOf(ecfg.in)
        }
        is(spec.Csr.Index.estat) {
          estat.in.is_softwareInt := writePort.data(1, 0)
        }
        is(
          spec.Csr.Index.era
        ) {
          era.in := writePort.data.asTypeOf(era.in)
        }
        is(
          spec.Csr.Index.badv
        ) {
          badv.in := writePort.data.asTypeOf(badv.in)
        }
        is(
          spec.Csr.Index.save0
        ) {
          saves(0).in := writePort.data.asTypeOf(saves(0).in)
        }
        is(
          spec.Csr.Index.save1
        ) {
          saves(1).in := writePort.data.asTypeOf(saves(0).in)
        }
        is(
          spec.Csr.Index.save2
        ) {
          saves(2).in := writePort.data.asTypeOf(saves(0).in)
        }
        is(
          spec.Csr.Index.save3
        ) {
          saves(3).in := writePort.data.asTypeOf(saves(0).in)
        }
        is(
          spec.Csr.Index.tid
        ) {
          tid.in := writePort.data.asTypeOf(tid.in)
        }
        is(spec.Csr.Index.eentry) {
          eentry.in := Cat(writePort.data(31, 6), 0.U(6.W)).asTypeOf(eentry.in)
        }
        is(spec.Csr.Index.cpuid) {
          // no write
        }
        is(spec.Csr.Index.llbctl) {
          llbctl.in.klo := writePort.data(2)
          when(writePort.data(1)) {
            llbctl.in.rollb := false.B
          }
        }
        is(spec.Csr.Index.tlbidx) {
          tlbidx.in := Cat(
            writePort.data(31),
            false.B,
            writePort.data(29, 24),
            0.U((24 - spec.Csr.Tlbidx.Width.index).W),
            writePort.data(spec.Csr.Tlbidx.Width.index - 1, 0)
          ).asTypeOf(tlbidx.in)
        }
        is(spec.Csr.Index.tlbehi) {
          tlbehi.in := Cat(writePort.data(31, 13), 0.U(13.W)).asTypeOf(tlbehi.in)
        }
        is(spec.Csr.Index.tlbelo0) {
          tlbelo0.in := Cat(
            writePort.data(31, 8),
            false.B,
            writePort.data(6, 0)
          ).asTypeOf(tlbelo0.in)
        }
        is(spec.Csr.Index.tlbelo1) {
          tlbelo1.in := Cat(
            writePort.data(31, 8),
            false.B,
            writePort.data(6, 0)
          ).asTypeOf(tlbelo1.in)
        }
        is(spec.Csr.Index.asid) {
          asid.in.asid := writePort.data(9, 0)
        }
        is(spec.Csr.Index.pgd) {
          // no write
        }
        is(spec.Csr.Index.pgdl) {
          pgdl.in := Cat(writePort.data(31, 12), 0.U(12.W)).asTypeOf(pgdl.in)
        }
        is(spec.Csr.Index.pgdh) {
          pgdh.in := Cat(writePort.data(31, 12), 0.U(12.W)).asTypeOf(pgdh.in)
        }
        is(spec.Csr.Index.tlbrentry) {
          tlbrentry.in := Cat(writePort.data(31, 6), 0.U(6.W)).asTypeOf(tlbrentry.in)
        }
        is(spec.Csr.Index.dmw0) {
          dmw0.in := Cat(
            writePort.data(31, 29),
            false.B,
            writePort.data(27, 25),
            0.U(19.W),
            writePort.data(5, 3),
            0.U(2.W),
            writePort.data(0)
          ).asTypeOf(dmw0.in)
        }
        is(spec.Csr.Index.dmw1) {
          dmw1.in := Cat(
            writePort.data(31, 29),
            false.B,
            writePort.data(27, 25),
            0.U(19.W),
            writePort.data(5, 3),
            0.U(2.W),
            writePort.data(0)
          ).asTypeOf(dmw1.in)
        }
        is(spec.Csr.Index.tcfg) {
          val initVal = WireDefault(writePort.data(spec.Csr.TimeVal.Width.timeVal - 1, 2))
          tcfg.in.initVal  := initVal
          tcfg.in.periodic := writePort.data(1)
          tcfg.in.en       := writePort.data(0)
          tval.in.timeVal  := Cat(initVal, 0.U(2.W))
        }
        is(spec.Csr.Index.tval) {
          // no write
        }
        is(spec.Csr.Index.ticlr) {
          when(writePort.data(0) === true.B) {
            estat.in.is_timeInt := false.B
          }
        }
      }
    }
  }

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
  estat.in.is_hardwareInt := io.csrMessage.hardWareInetrrupt
  when(io.csrMessage.exceptionFlush) {
    estat.in.ecode    := io.csrMessage.ecodeBundle.ecode
    estat.in.esubcode := io.csrMessage.ecodeBundle.esubcode
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
    when(!llbctl.out.klo) {
      llbctl.in.rollb := false.B
    }
    llbctl.in.klo := false.B
  }

  // 中断
  // la 空出来了一位
  estat.in.is_hardwareInt := Cat(false.B, io.csrMessage.hardWareInetrrupt)

//  val hasInterrupt = ((estat.out.asUInt)(12, 2) & ecfg.out.lie(12, 2)).orR && crmd.out.ie
  val hasInterrupt = ((estat.out.asUInt)(12, 0) & ecfg.out.lie(12, 0)).orR && crmd.out.ie
  io.hasInterrupt := hasInterrupt && !RegNext(hasInterrupt)

  // output
  io.csrValues.crmd      := crmd.out
  io.csrValues.prmd      := prmd.out
  io.csrValues.euen      := euen.out
  io.csrValues.ecfg      := ecfg.out
  io.csrValues.estat     := estat.out
  io.csrValues.era       := era.out
  io.csrValues.badv      := badv.out
  io.csrValues.eentry    := eentry.out
  io.csrValues.tlbidx    := tlbidx.out
  io.csrValues.tlbehi    := tlbehi.out
  io.csrValues.tlbelo0   := tlbelo0.out
  io.csrValues.tlbelo1   := tlbelo1.out
  io.csrValues.asid      := asid.out
  io.csrValues.pgdl      := pgdl.out
  io.csrValues.pgdh      := pgdh.out
  io.csrValues.pgd       := pgd.out
  io.csrValues.cpuid     := cpuid.out
  io.csrValues.save0     := saves(0).out
  io.csrValues.save1     := saves(1).out
  io.csrValues.save2     := saves(2).out
  io.csrValues.save3     := saves(3).out
  io.csrValues.tid       := tid.out
  io.csrValues.tcfg      := tcfg.out
  io.csrValues.tval      := tval.out
  io.csrValues.ticlr     := ticlr.out
  io.csrValues.llbctl    := llbctl.out
  io.csrValues.tlbrentry := tlbrentry.out
  io.csrValues.dmw0      := dmw0.out
  io.csrValues.dmw1      := dmw1.out

}
