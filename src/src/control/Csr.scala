package control

import chisel3._
import chisel3.util._
import control.bundles._
import control.csrBundles._
import memory.bundles.{TlbCsrWriteNdPort, TransExceptionCsrNdPort}
import spec._

class Csr(
  writeNum: Int = Param.csrWriteNum,
  readNum:  Int = Param.csrReadNum)
    extends Module {
  val io = IO(new Bundle {
    // `Cu` and `Tlb` -> `Csr`
    val tlbMaintenanceWritePort = Flipped(Valid(new TlbCsrWriteNdPort))
    // `Cu` and `Tlb` -> `Csr`
    val tlbExceptionWritePorts = Flipped(Vec(Param.Count.Tlb.transNum, Valid(new TransExceptionCsrNdPort)))

    val writePorts = Input(Vec(writeNum, new CsrWriteNdPort))
    val csrMessage = Input(new CuToCsrNdPort)
    val csrValues  = Output(new CsrValuePort)
    // `Csr` <-> `RegReadStage`
    val readPorts = Vec(Param.csrReadNum, new CsrReadPort)
    // `Csr` -> `WbStage`
    val hasInterrupt = Output(Bool())
    // `Csr` -> Cu`
    val csrFlushRequest = Output(Bool())
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

  val csr = RegInit(VecInit(Seq.fill(Count.csrReg)(zeroWord)))

  // CRMD 当前模式信息

  val crmd = viewUInt(csr(spec.Csr.Index.crmd), new CrmdBundle)

  // PRMD 例外前模式信息
  val prmd = viewUInt(csr(spec.Csr.Index.prmd), new PrmdBundle)

  // EUEN扩展部件使能
  val euen = viewUInt(csr(spec.Csr.Index.euen), new EuenBundle)

  // ECFG 例外控制
  val ecfg = viewUInt(csr(spec.Csr.Index.ecfg), new EcfgBundle)

  // ESTAT
  val estat = viewUInt(csr(spec.Csr.Index.estat), new EstatBundle)

  // ERA 例外返回地址: 触发例外指令的pc记录在此
  val era = viewUInt(csr(spec.Csr.Index.era), new EraBundle)

  // BADV 出错虚地址
  val badv = viewUInt(csr(spec.Csr.Index.badv), new BadvBundle)

  // EENTRY 例外入口地址
  val eentry = viewUInt(csr(spec.Csr.Index.eentry), new EentryBundle)

  // CPUID 处理器编号
  val cpuid = viewUInt(csr(spec.Csr.Index.cpuid), new CpuidBundle)

  // SAVE0-3 数据保存
  val saves = VecInit(spec.Csr.Index.save0, spec.Csr.Index.save1, spec.Csr.Index.save2, spec.Csr.Index.save3).map {
    idx =>
      viewUInt(csr(idx), new CsrSaveBundle)
  }

  // LLBCTL  LLBit控制
  val llbctl = viewUInt(csr(spec.Csr.Index.llbctl), new LlbctlBundle)

  // TLBIDX  TLB索引
  val tlbidx = viewUInt(csr(spec.Csr.Index.tlbidx), new TlbidxBundle)

  // TLBEHI  TLB表项高位
  val tlbehi = viewUInt(csr(spec.Csr.Index.tlbehi), new TlbehiBundle)

  // TLBELO 0-1  TLB表项低位
  val tlbelo0 = viewUInt(csr(spec.Csr.Index.tlbelo0), new TlbeloBundle)

  val tlbelo1 = viewUInt(csr(spec.Csr.Index.tlbelo1), new TlbeloBundle)

  // ASID 地址空间标识符
  val asid = viewUInt(csr(spec.Csr.Index.asid), new AsidBundle)

  // PGDL 低半地址空间全局目录基址
  val pgdl = viewUInt(csr(spec.Csr.Index.pgdl), new PgdlBundle)

  // PGDH 高半地址空间全局目录基址
  val pgdh = viewUInt(csr(spec.Csr.Index.pgdh), new PgdhBundle)

  // PGD 全局地址空间全局目录基址
  val pgd = viewUInt(csr(spec.Csr.Index.pgd), new PgdBundle)

  // TLBRENTRY  TLB重填例外入口地址
  val tlbrentry = viewUInt(csr(spec.Csr.Index.tlbrentry), new TlbrentryBundle)

  // DMW 0-1 直接映射配置窗口
  val dmw0 = viewUInt(csr(spec.Csr.Index.dmw0), new DmwBundle)

  val dmw1 = viewUInt(csr(spec.Csr.Index.dmw1), new DmwBundle)

  // TID 定时器编号
  val tid = viewUInt(csr(spec.Csr.Index.tid), new TidBundle)

  // TCFG 定时器配置
  val tcfg = viewUInt(csr(spec.Csr.Index.tcfg), new TcfgBundle)

  // TVAL 定时器数值
  val tval = viewUInt(csr(spec.Csr.Index.tval), new TvalBundle)

  // TICLR 定时器中断清除
  val ticlr = viewUInt(csr(spec.Csr.Index.ticlr), new TiclrBundle)

  // read
  io.readPorts.foreach { readPort =>
    readPort.data := zeroWord
    when(readPort.en) {
      readPort.data := csr(readPort.addr)
      when(readPort.addr === spec.Csr.Index.pgd) {
        readPort.data := Mux(
          badv.out.vaddr(31),
          csr(spec.Csr.Index.pgdh),
          csr(spec.Csr.Index.pgdl)
        )
      }
    }
  }

  // TimeVal

  when(tcfg.out.en) {
    when(tval.out.timeVal === 0.U) {
      tval.in.timeVal := Mux(
        tcfg.out.periodic,
        tcfg.out.initVal << 2,
        "hffffffff".U(32.W)
      )
      estat.in.is_timeInt := true.B
    }.otherwise {
      tval.in.timeVal := tval.out.timeVal - 1.U
    }
  }

  // 软件写csr
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
          prmd.in.pplv := writePort.data(1, 0)
          prmd.in.pie  := writePort.data(2)
        }
        is(spec.Csr.Index.euen) {
          euen.in.fpe := writePort.data(0)
        }
        is(spec.Csr.Index.ecfg) {
          ecfg.in.lie := writePort.data(12, 0)
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
          eentry.in.va := writePort.data(31, 6)
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
          tlbidx.in.index := writePort.data(spec.Csr.Tlbidx.Width.index - 1, 0)
          tlbidx.in.ps    := writePort.data(29, 24)
          tlbidx.in.ne    := writePort.data(31)
        }
        is(spec.Csr.Index.tlbehi) {
          tlbehi.in.vppn := writePort.data(31, 13)
        }
        is(spec.Csr.Index.tlbelo0) {
          tlbelo0.in.v   := writePort.data(0)
          tlbelo0.in.d   := writePort.data(1)
          tlbelo0.in.plv := writePort.data(3, 2)
          tlbelo0.in.mat := writePort.data(5, 4)
          tlbelo0.in.g   := writePort.data(6)
          tlbelo0.in.ppn := writePort.data(spec.Csr.Tlbelo.Width.palen - 5, 8)
        }
        is(spec.Csr.Index.tlbelo1) {
          tlbelo1.in.v   := writePort.data(0)
          tlbelo1.in.d   := writePort.data(1)
          tlbelo1.in.plv := writePort.data(3, 2)
          tlbelo1.in.mat := writePort.data(5, 4)
          tlbelo1.in.g   := writePort.data(6)
          tlbelo1.in.ppn := writePort.data(spec.Csr.Tlbelo.Width.palen - 5, 8)
        }
        is(spec.Csr.Index.asid) {
          asid.in.asid := writePort.data(9, 0)
        }
        is(spec.Csr.Index.pgd) {
          // no write
        }
        is(spec.Csr.Index.pgdl) {
          pgdl.in.base := writePort.data(31, 12)
        }
        is(spec.Csr.Index.pgdh) {
          pgdh.in.base := writePort.data(31, 12)
        }
        is(spec.Csr.Index.tlbrentry) {
          tlbrentry.in.pa := writePort.data(31, 6)
        }
        is(spec.Csr.Index.dmw0) {
          dmw0.in.plv0 := writePort.data(0)
          dmw0.in.plv3 := writePort.data(3)
          dmw0.in.mat  := writePort.data(5, 4)
          dmw0.in.pseg := writePort.data(27, 25)
          dmw0.in.vseg := writePort.data(31, 29)
        }
        is(spec.Csr.Index.dmw1) {
          dmw1.in.plv0 := writePort.data(0)
          dmw1.in.plv3 := writePort.data(3)
          dmw1.in.mat  := writePort.data(5, 4)
          dmw1.in.pseg := writePort.data(27, 25)
          dmw1.in.vseg := writePort.data(31, 29)
        }
        is(spec.Csr.Index.tcfg) {
          val initVal = WireDefault(writePort.data(spec.Csr.TimeVal.Width.timeVal - 1, 2))
          tcfg.in.initVal  := initVal
          tcfg.in.periodic := writePort.data(1)
          tcfg.in.en       := writePort.data(0)
          tval.in.timeVal  := initVal << 2
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
  estat.in.is_hardwareInt := io.csrMessage.hardwareInterrupt
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

  // TLB exception write
  val tlbExceptionWriteValid = io.tlbExceptionWritePorts.map(_.valid).reduce(_ || _)
  val tlbExceptionWriteIndex = OHToUInt(io.tlbExceptionWritePorts.map(_.valid))
  val tlbExceptionWriteBits  = io.tlbExceptionWritePorts(tlbExceptionWriteIndex).bits
  when(tlbExceptionWriteValid) {
    tlbehi.in.vppn := io.csrMessage.badVAddrSet.addr(31, 13) // tlbExceptionWriteBits.vppn
  }

  // TLB maintenance write
  val tlbMaintenanceWrite = io.tlbMaintenanceWritePort.bits
  when(io.tlbMaintenanceWritePort.valid) {
    when(tlbMaintenanceWrite.tlbidx.valid) {
      tlbidx.in := tlbMaintenanceWrite.tlbidx.bits
    }
    when(tlbMaintenanceWrite.tlbehi.valid) {
      tlbehi.in := tlbMaintenanceWrite.tlbehi.bits
    }
    when(tlbMaintenanceWrite.tlbeloVec(0).valid) {
      tlbelo0.in := tlbMaintenanceWrite.tlbeloVec(0).bits
    }
    when(tlbMaintenanceWrite.tlbeloVec(1).valid) {
      tlbelo1.in := tlbMaintenanceWrite.tlbeloVec(1).bits
    }
    when(tlbMaintenanceWrite.asId.valid) {
      asid.in := tlbMaintenanceWrite.asId.bits
    }
  }

  // 中断
  // la 最高位空出来了一位
  estat.in.is_hardwareInt := io.csrMessage.hardwareInterrupt

  val hasInterrupt = ((estat.out.asUInt)(12, 0) & ecfg.out.lie(12, 0)).orR && crmd.out.ie
  io.hasInterrupt := hasInterrupt && !RegNext(hasInterrupt)

  // crmd / dmw change should flush all pipeline
  io.csrFlushRequest := ((crmd.in.asUInt =/= crmd.out.asUInt) ||
    (dmw0.in.asUInt =/= dmw0.out.asUInt) ||
    (dmw1.in.asUInt =/= dmw1.out.asUInt)) &&
    !io.csrMessage.ertnFlush &&
    !io.csrMessage.exceptionFlush

  // Output
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
