package axi

import axi.Arbiter
import axi.bundles.{MasterWrite, SlaveWrite}
import axi.types.RegType._
import chisel3._
import chisel3.util._
import spec._

class AxiCrossbarWrite(
  slaveAwRegType:     Seq[RegType] = Seq.fill(Param.Count.Axi.slave)(BYPASS),
  slaveWRegType:      Seq[RegType] = Seq.fill(Param.Count.Axi.slave)(BYPASS),
  slaveBRegType:      Seq[RegType] = Seq.fill(Param.Count.Axi.slave)(SIMPLE_BUFFER),
  masterAwRegType:    Seq[RegType] = Seq.fill(Param.Count.Axi.master)(SIMPLE_BUFFER),
  masterWRegType:     Seq[RegType] = Seq.fill(Param.Count.Axi.master)(SKID_BUFFER),
  masterBRegType:     Seq[RegType] = Seq.fill(Param.Count.Axi.master)(BYPASS),
  masterIssue:        Seq[Int]     = Seq.fill(Param.Count.Axi.master)(4),
  val masterBaseAddr: Int          = 0 // set to zero for default addressing
) extends Module {

  private val masterCount = Param.Count.Axi.master
  private val slaveCount  = Param.Count.Axi.slave

  val io = IO(new Bundle {
    val slaves  = Vec(slaveCount, new SlaveWrite)
    val masters = Vec(masterCount, new MasterWrite)
  })

  val IntSlavesAw = Wire(
    Vec(
      slaveCount,
      new Bundle {
        val id     = UInt(Param.Width.Axi.slaveId.W)
        val addr   = UInt(Width.Axi.addr.W)
        val len    = UInt(8.W)
        val size   = UInt(3.W)
        val burst  = UInt(2.W)
        val lock   = Bool()
        val cache  = UInt(4.W)
        val prot   = UInt(3.W)
        val qos    = UInt(4.W)
        val region = UInt(4.W)
        val user   = UInt(Width.Axi.awuser.W)
        val valid  = Bool()
        val ready  = Bool()
      }
    )
  )
  val IntAwValid = Wire(Vec(slaveCount, UInt(masterCount.W)))
  val IntAwReady = Wire(Vec(masterCount, UInt(slaveCount.W)))
  val IntSlavesW = Wire(
    Vec(
      slaveCount,
      new Bundle {
        val data  = UInt(Width.Axi.data.W)
        val strb  = UInt(Width.Axi.strb.W)
        val last  = Bool()
        val user  = UInt(Width.Axi.wuser.W)
        val valid = Bool()
        val ready = Bool()
      }
    )
  )
  val IntWValid = Wire(Vec(slaveCount, UInt(masterCount.W)))
  val IntWReady = Wire(Vec(masterCount, UInt(slaveCount.W)))
  val IntMastersB = Wire(
    Vec(
      masterCount,
      new Bundle {
        val id    = UInt(Param.Width.Axi.masterId.W)
        val resp  = UInt(2.W)
        val user  = UInt(Width.Axi.buser.W)
        val valid = Bool()
        val ready = Bool()
      }
    )
  )
  val IntBValid = Wire(Vec(masterCount, UInt(slaveCount.W)))
  val IntBReady = Wire(Vec(slaveCount, UInt(masterCount.W)))

  for (index <- 0 until slaveCount) {
    val aSelect      = Wire(UInt(log2Ceil(masterCount).W))
    val masterAValid = Wire(Bool())
    val masterAReady = Wire(Bool())

    val masterWcSelect = Wire(UInt(log2Ceil(masterCount).W))
    val masterWcDecerr = Wire(Bool())
    val masterWcValid  = Wire(Bool())
    val masterWcReady  = Wire(Bool())

    val masterRcDecerr = Wire(Bool())
    val masterRcValid  = Wire(Bool())
    val masterRcReady  = Wire(Bool())

    val slaveCplId    = Wire(UInt(Param.Width.Axi.slaveId.W))
    val slaveCplValid = Wire(Bool())

    val addrInst = Module(
      new AxiCrossbarAddr(
        slaveIndex               = index,
        idWidth                  = Param.Width.Axi.slaveId,
        masterBaseAddr           = masterBaseAddr,
        writeCommandOutputEnable = true
      )
    )
    // address input
    addrInst.io.slaveAid <> IntSlavesAw(index).id
    addrInst.io.slaveAaddr <> IntSlavesAw(index).addr
    addrInst.io.slaveAprot <> IntSlavesAw(index).prot
    addrInst.io.slaveAqos <> IntSlavesAw(index).qos
    addrInst.io.slaveAvalid <> IntSlavesAw(index).valid
    addrInst.io.slaveAready <> IntSlavesAw(index).ready
    // address output
    addrInst.io.masterAregion <> IntSlavesAw(index).region
    addrInst.io.masterSelect <> aSelect
    addrInst.io.masterAvalid <> masterAValid
    addrInst.io.masterAready <> masterAReady
    // write command output
    addrInst.io.masterWriteCommandSelect <> masterWcSelect
    addrInst.io.masterWriteCommandDecerr <> masterWcDecerr
    addrInst.io.masterWriteCommandValid <> masterWcValid
    addrInst.io.masterWriteCommandReady <> masterWcReady
    // response command output
    addrInst.io.masterReplyCommandDecerr <> masterRcDecerr
    addrInst.io.masterReplyCommandValid <> masterRcValid
    addrInst.io.masterReplyCommandReady <> masterRcReady
    // completion input
    addrInst.io.slaveCompletionId <> slaveCplId
    addrInst.io.slaveCompletionValid <> slaveCplValid

    IntAwValid(index) := (masterAValid << aSelect)
    masterAReady      := IntAwReady(aSelect)(index)

    // write command handling
    val wSelectNext      = Wire(UInt(log2Ceil(masterCount).W))
    val wSelectReg       = RegNext(wSelectNext)
    val wDropNext        = Wire(Bool())
    val wDropReg         = RegNext(wDropNext)
    val wSelectValidNext = Wire(Bool())
    val wSelectValidReg  = RegNext(wSelectValidNext, false.B)
    wDropReg        := false.B
    wSelectValidReg := false.B

    masterWcReady := !wSelectValidReg

    when(masterWcValid && !wSelectValidReg) {
      wSelectNext      := wSelectReg
      wDropNext        := masterWcDecerr
      wSelectValidNext := masterWcValid
    }.otherwise {
      wSelectNext := wSelectReg
      wDropNext   := wDropReg && !(IntSlavesW(index).valid && IntSlavesW(index).ready && IntSlavesW(index).last)
      wSelectValidNext := wSelectValidReg && !(IntSlavesW(index).valid && IntSlavesW(index).ready && IntSlavesW(
        index
      ).last)
    }

    // write data forwarding
    IntWValid(index)        := (IntSlavesW(index).valid && wSelectValidReg && !wDropReg) << wSelectReg
    IntSlavesW(index).ready := IntWReady(wSelectReg)(index) || wDropReg

    // decode error handling
    val decerrMasterBidReg  = Reg(UInt(slaveCount.W))
    val decerrMasterBidNext = Wire(UInt(slaveCount.W))
    decerrMasterBidReg := 0.U
    val decerrMasterBvalidNext = Wire(Bool())
    val decerrMasterBvalidReg  = RegNext(decerrMasterBvalidNext, false.B)
    decerrMasterBvalidReg := false.B
    val decerrMasterBready = Wire(Bool())

    masterRcReady := !decerrMasterBvalidReg

    decerrMasterBidNext    := decerrMasterBidReg
    decerrMasterBvalidNext := decerrMasterBvalidReg

    when(decerrMasterBvalidReg) {
      when(decerrMasterBready) {
        decerrMasterBvalidNext := false.B
      }
    }.elsewhen(masterRcValid && masterRcReady) {
      decerrMasterBidNext    := IntSlavesAw(index).id(index)
      decerrMasterBvalidNext := true.B
    }

    decerrMasterBidReg := decerrMasterBidNext

    // write response arbitration
    val bRequest      = Wire(Vec(masterCount + 1, Bool()))
    val bAcknowledge  = Wire(Vec(masterCount + 1, Bool()))
    val bGrant        = Wire(UInt((masterCount + 1).W))
    val bGrantValid   = Wire(Bool())
    val bGrantEncoded = Wire(UInt(log2Ceil(masterCount + 1).W))
    val bArbiter      = Module(new Arbiter(ports = masterCount + 1))
    bArbiter.io.request <> bRequest.asUInt
    bArbiter.io.acknowledge <> bAcknowledge.asUInt
    bArbiter.io.grant <> bGrant
    bArbiter.io.grantValid <> bGrantValid
    bArbiter.io.grantEncoded <> bGrantEncoded

    // write response mux
    val masterBidMux    = Wire(UInt(Param.Width.Axi.slaveId.W))
    val masterBrespMux  = Wire(UInt(2.W))
    val masterBuserMux  = Wire(UInt(Width.Axi.buser.W))
    val masterBvalidMux = Wire(Bool())
    val masterBreadyMux = Wire(Bool())

    masterBidMux := IntMastersB.foldLeft(decerrMasterBidReg)((result, item) =>
      Cat(result, item.id)
    ) >> (bGrantEncoded * Param.Width.Axi.masterId.U)
    masterBrespMux := IntMastersB.foldLeft(3.U(2.W))((result, item) => Cat(result, item.resp)) >> (bGrantEncoded * 2.U)
    masterBuserMux := IntMastersB.foldLeft(0.U(Width.Axi.buser.W))((result, item) =>
      Cat(result, item.user)
    ) >> (bGrantEncoded * Width.Axi.buser.U)
    masterBvalidMux := IntMastersB.foldLeft(decerrMasterBvalidReg.asUInt)((result, item) =>
      Cat(result, item.valid)
    ) >> bGrantValid

    IntBReady(index)   := (bGrantValid && masterBreadyMux) << bGrantEncoded
    decerrMasterBready := (bGrantValid && masterBreadyMux) && (bGrantEncoded === masterCount.U)

    for (n <- 0 until masterCount) {
      bRequest(n)     := IntBValid(n)(index) && !bGrant(n)
      bAcknowledge(n) := bGrant(n) && IntBValid(n)(index) && masterBreadyMux
    }

    bRequest(masterCount)     := decerrMasterBvalidReg && !bGrant(masterCount)
    bAcknowledge(masterCount) := bGrant(masterCount) && decerrMasterBvalidReg && masterBreadyMux

    slaveCplId    := masterBidMux
    slaveCplValid := masterBvalidMux && masterBreadyMux

    val regInst = Module(
      new AxiRegisterWrite(
        awRegType = slaveAwRegType(index),
        wRegType  = slaveWRegType(index),
        bRegType  = slaveBRegType(index)
      )
    )
    regInst.io.slave.aw.ready <> io.slaves(index).aw.ready
    regInst.io.slave.aw.valid <> io.slaves(index).aw.valid
    regInst.io.slave.aw.bits.id <> io.slaves(index).aw.bits.id
    regInst.io.slave.aw.bits.addr <> io.slaves(index).aw.bits.addr
    regInst.io.slave.aw.bits.len <> io.slaves(index).aw.bits.len
    regInst.io.slave.aw.bits.size <> io.slaves(index).aw.bits.size
    regInst.io.slave.aw.bits.burst <> io.slaves(index).aw.bits.burst
    regInst.io.slave.aw.bits.lock <> io.slaves(index).aw.bits.lock
    regInst.io.slave.aw.bits.cache <> io.slaves(index).aw.bits.cache
    regInst.io.slave.aw.bits.prot <> io.slaves(index).aw.bits.prot
    regInst.io.slave.aw.bits.qos <> io.slaves(index).aw.bits.qos
    regInst.io.slave.aw.bits.region <> 0.U
    regInst.io.slave.aw.bits.user <> io.slaves(index).aw.bits.user
    regInst.io.slave.w <> io.slaves(index).w
    regInst.io.slave.b <> io.slaves(index).b
    regInst.io.master.aw.bits.id <> IntSlavesAw(index).id
    regInst.io.master.aw.bits.addr <> IntSlavesAw(index).addr
    regInst.io.master.aw.bits.len <> IntSlavesAw(index).len
    regInst.io.master.aw.bits.size <> IntSlavesAw(index).size
    regInst.io.master.aw.bits.burst <> IntSlavesAw(index).burst
    regInst.io.master.aw.bits.lock <> IntSlavesAw(index).lock
    regInst.io.master.aw.bits.cache <> IntSlavesAw(index).cache
    regInst.io.master.aw.bits.prot <> IntSlavesAw(index).prot
    regInst.io.master.aw.bits.qos <> IntSlavesAw(index).qos
    regInst.io.master.aw.bits.region <> DontCare
    regInst.io.master.aw.bits.user <> IntSlavesAw(index).user
    regInst.io.master.aw.valid <> IntSlavesAw(index).valid
    regInst.io.master.aw.ready <> IntSlavesAw(index).ready
    regInst.io.master.w.bits.data <> IntSlavesW(index).data
    regInst.io.master.w.bits.strb <> IntSlavesW(index).strb
    regInst.io.master.w.bits.last <> IntSlavesW(index).last
    regInst.io.master.w.bits.user <> IntSlavesW(index).user
    regInst.io.master.w.valid <> IntSlavesW(index).valid
    regInst.io.master.w.ready <> IntSlavesW(index).ready
    regInst.io.master.b.bits.id <> masterBidMux
    regInst.io.master.b.bits.resp <> masterBrespMux
    regInst.io.master.b.bits.user <> masterBuserMux
    regInst.io.master.b.valid <> masterBvalidMux
    regInst.io.master.b.ready <> masterBreadyMux
  }

  for (index <- 0 until masterCount) {
    // in-flight transaction count
    val transStart    = Wire(Bool())
    val transComplete = Wire(Bool())
    val transCountReg = RegInit(0.U(log2Ceil(masterIssue(index) + 1).W))

    val transLimit = transCountReg >= masterIssue(index).U && !transComplete
    when(transStart && !transComplete) {
      transCountReg := transCountReg + 1.U
    }.elsewhen(!transStart && transComplete) {
      transCountReg := transCountReg - 1.U
    }

    // address arbitration
    val wSelectNext      = Wire(UInt(log2Ceil(slaveCount).W))
    val wSelectReg       = RegNext(wSelectNext)
    val wSelectValidNext = Wire(Bool())
    val wSelectValidReg  = RegNext(wSelectValidNext, false.B)
    val wSelectNewNext   = Wire(Bool())
    val wSelectNewReg    = RegNext(wSelectNewNext, true.B)

    val aRequest      = Wire(Vec(slaveCount, Bool()))
    val aAcknowledge  = Wire(Vec(slaveCount, Bool()))
    val aGrant        = Wire(UInt(slaveCount.W))
    val aGrantValid   = Wire(Bool())
    val aGrantEncoded = Wire(UInt(log2Ceil(slaveCount).W))

    val aArb = Module(new Arbiter(ports = slaveCount))
    aArb.io.request <> aRequest.asUInt
    aArb.io.acknowledge <> aAcknowledge.asUInt
    aArb.io.grant <> aGrant
    aArb.io.grantValid <> aGrantValid
    aArb.io.grantEncoded <> aGrantEncoded

    // address mux
    val slaveAwidMux     = Wire(UInt(Param.Width.Axi.masterId.W))
    val slaveAwaddrMux   = Wire(UInt(Width.Axi.addr.W))
    val slaveAwlenMux    = Wire(UInt(8.W))
    val slaveAwsizeMux   = Wire(UInt(3.W))
    val slaveAwburstMux  = Wire(UInt(2.W))
    val slaveAwlockMux   = Wire(Bool())
    val slaveAwcacheMux  = Wire(UInt(4.W))
    val slaveAwprotMux   = Wire(UInt(3.W))
    val slaveAwqosMux    = Wire(UInt(4.W))
    val slaveAwregionMux = Wire(UInt(4.W))
    val slaveAwuserMux   = Wire(UInt(Width.Axi.awuser.W))
    val slaveAwvalidMux  = Wire(Bool())
    val slaveAwreadyMux  = Wire(Bool())
    slaveAwidMux     := IntSlavesAw(aGrantEncoded).id | (aGrantEncoded << Param.Width.Axi.slaveId)
    slaveAwaddrMux   := IntSlavesAw(aGrantEncoded).addr
    slaveAwlenMux    := IntSlavesAw(aGrantEncoded).len
    slaveAwsizeMux   := IntSlavesAw(aGrantEncoded).size
    slaveAwburstMux  := IntSlavesAw(aGrantEncoded).burst
    slaveAwlockMux   := IntSlavesAw(aGrantEncoded).lock
    slaveAwcacheMux  := IntSlavesAw(aGrantEncoded).cache
    slaveAwprotMux   := IntSlavesAw(aGrantEncoded).prot
    slaveAwqosMux    := IntSlavesAw(aGrantEncoded).qos
    slaveAwregionMux := IntSlavesAw(aGrantEncoded).region
    slaveAwuserMux   := IntSlavesAw(aGrantEncoded).user
    slaveAwvalidMux  := IntAwValid(aGrantEncoded)(index) && aGrantValid

    IntAwReady(index) := (aGrantValid && slaveAwreadyMux) << aGrantEncoded

    for (m <- 0 until slaveCount) {
      aRequest(m)     := IntAwValid(m)(index) && !aGrant(m) && !transLimit && !wSelectValidNext
      aAcknowledge(m) := aGrant(m) && IntAwValid(m)(index) && slaveAwreadyMux
    }

    transStart := slaveAwvalidMux && slaveAwreadyMux && aGrantValid

    // write data mux
    val slaveWdataMux  = Wire(UInt(Width.Axi.data.W))
    val slaveWstrbMux  = Wire(UInt(Width.Axi.strb.W))
    val slaveWlastMux  = Wire(Bool())
    val slaveWuserMux  = Wire(UInt(Width.Axi.wuser.W))
    val slaveWvalidMux = Wire(Bool())
    val slaveWreadyMux = Wire(Bool())
    slaveWdataMux  := IntSlavesW(wSelectReg).data
    slaveWstrbMux  := IntSlavesW(wSelectReg).strb
    slaveWlastMux  := IntSlavesW(wSelectReg).last
    slaveWuserMux  := IntSlavesW(wSelectReg).user
    slaveWvalidMux := IntSlavesW(wSelectReg).valid

    IntWReady(index) := (wSelectValidReg && slaveWreadyMux) << wSelectReg

    // write data routing
    when(aGrantValid && !wSelectValidReg && wSelectNewReg) {
      wSelectNext      := aGrantEncoded
      wSelectValidNext := aGrantValid
      wSelectNewNext   := false.B
    }.otherwise {
      wSelectNext      := wSelectReg
      wSelectValidNext := wSelectValidReg && !(slaveWvalidMux && slaveWreadyMux && slaveWlastMux)
      wSelectNewNext   := false.B
    }

    // write response forwarding
    val bSelect = Wire(UInt(log2Ceil(slaveCount).W))
    bSelect := io.masters(index).b.bits.id >> Param.Width.Axi.slaveId.U

    IntBValid(index)         := IntMastersB(index).valid << bSelect
    IntMastersB(index).ready := IntBReady(bSelect)(index)

    transComplete := IntMastersB(index).valid && IntMastersB(index).ready

    // M side register
    val regInst = Module(
      new AxiRegisterWrite(
        awRegType = masterAwRegType(index),
        wRegType  = masterWRegType(index),
        bRegType  = masterBRegType(index)
      )
    )
    regInst.io.slave.aw.bits.id <> slaveAwidMux
    regInst.io.slave.aw.bits.addr <> slaveAwaddrMux
    regInst.io.slave.aw.bits.len <> slaveAwlenMux
    regInst.io.slave.aw.bits.size <> slaveAwsizeMux
    regInst.io.slave.aw.bits.burst <> slaveAwburstMux
    regInst.io.slave.aw.bits.lock <> slaveAwlockMux
    regInst.io.slave.aw.bits.cache <> slaveAwcacheMux
    regInst.io.slave.aw.bits.prot <> slaveAwprotMux
    regInst.io.slave.aw.bits.qos <> slaveAwqosMux
    regInst.io.slave.aw.bits.region <> slaveAwregionMux
    regInst.io.slave.aw.bits.user <> slaveAwuserMux
    regInst.io.slave.aw.valid <> slaveAwvalidMux
    regInst.io.slave.aw.ready <> slaveAwreadyMux
    regInst.io.slave.w.bits.data <> slaveWdataMux
    regInst.io.slave.w.bits.strb <> slaveWstrbMux
    regInst.io.slave.w.bits.last <> slaveWlastMux
    regInst.io.slave.w.bits.user <> slaveWuserMux
    regInst.io.slave.w.valid <> slaveWvalidMux
    regInst.io.slave.w.ready <> slaveWreadyMux
    regInst.io.slave.b.bits.id <> IntMastersB(index).id
    regInst.io.slave.b.bits.resp <> IntMastersB(index).resp
    regInst.io.slave.b.bits.user <> IntMastersB(index).user
    regInst.io.slave.b.valid <> IntMastersB(index).valid
    regInst.io.slave.b.ready <> IntMastersB(index).ready
    regInst.io.master <> io.masters(index)
  }
}
