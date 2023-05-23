package control.bundles

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import spec._
import control.csrRegsBundles._
import memory.bundles.TlbEntryBundle

class CsrValuePort extends Bundle {
  val crmd      = new CrmdBundle
  val prmd      = new PrmdBundle
  val euen      = new EuenBundle
  val ecfg      = new EcfgBundle
  val estat     = new EstatBundle
  val era       = new EraBundle
  val badv      = new BadvBundle
  val eentry    = new EentryBundle
  val tlbidx    = new TlbidxBundle
  val tlbehi    = new TlbehiBundle
  val tlbelo0   = new TlbeloBundle
  val tlbelo1   = new TlbeloBundle
  val asid      = new AsidBundle
  val pgdl      = new PgdlBundle
  val pgdh      = new PgdhBundle
  val pgd       = new PgdBundle
  val cpuid     = new CpuidBundle
  val save0     = new CsrSaveBundle
  val save1     = new CsrSaveBundle
  val save2     = new CsrSaveBundle
  val save3     = new CsrSaveBundle
  val tid       = new TidBundle
  val tcfg      = new TcfgBundle
  val tval      = new TvalBundle
  val ticlr     = new TiclrBundle
  val llbctl    = new LlbctlBundle
  val tlbrentry = new TlbEntryBundle
  val dmw0      = new DmwBundle
  val dmw1      = new DmwBundle

}

object CsrValuePort {
  val default = 0.U.asTypeOf(new CsrValuePort)
}
