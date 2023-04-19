package axi

import axi.Arbiter
import axi.bundles.{MasterRead, SlaveRead}
import axi.types.RegType._
import chisel3._
import chisel3.util._
import spec._

class AxiCrossbarRead(
  slaveArRegType:     Seq[RegType] = Seq.fill(Param.Count.Axi.slave)(BYPASS),
  slaveRRegType:      Seq[RegType] = Seq.fill(Param.Count.Axi.slave)(SKID_BUFFER),
  masterArRegType:    Seq[RegType] = Seq.fill(Param.Count.Axi.master)(SIMPLE_BUFFER),
  masterRRegType:     Seq[RegType] = Seq.fill(Param.Count.Axi.master)(BYPASS),
  masterIssue:        Seq[Int]     = Seq.fill(Param.Count.Axi.master)(4),
  val masterBaseAddr: Int          = 0 // set to zero for default addressing
) extends Module {

  private val masterCount = Param.Count.Axi.master
  private val slaveCount  = Param.Count.Axi.slave

  val io = IO(new Bundle {
    val slaves  = Vec(slaveCount, new SlaveRead)
    val masters = Vec(masterCount, new MasterRead)
  })

  val IntSlavesAr = Wire(
    Vec(
      slaveCount,
      new Bundle {
        val id     = UInt(Param.Width.Axi.slaveId.W)
        val addr   = UInt(Width.Axi.addr)
        val len    = UInt(8.W)
        val size   = UInt(3.W)
        val burst  = UInt(2.W)
        val lock   = Bool()
        val cache  = UInt(4.W)
        val prot   = UInt(3.W)
        val qos    = UInt(4.W)
        val region = UInt(4.W)
        val user   = UInt(Width.Axi.aruser)
        val valid  = Bool()
        val ready  = Bool()
      }
    )
  )
  val IntArValid = Wire(Vec(slaveCount, UInt(masterCount.W)))
  val IntArReady = Wire(Vec(masterCount, UInt(slaveCount.W)))
  val IntMastersR = Wire(
    Vec(
      masterCount,
      new Bundle {
        val id    = UInt(Param.Width.Axi.masterId.W)
        val data  = UInt(Width.Axi.data)
        val resp  = UInt(2.W)
        val last  = Bool()
        val user  = UInt(Width.Axi.ruser)
        val valid = Bool()
        val ready = Bool()
      }
    )
  )
  val IntRValid = Wire(Vec(masterCount, UInt(slaveCount.W)))
  val IntRReady = Wire(Vec(slaveCount, UInt(masterCount.W)))

  for (index <- 0 until slaveCount) {
    val aSelect      = Wire(UInt(log2Ceil(masterCount).W))
    val masterAValid = Wire(Bool())
    val masterAReady = Wire(Bool())

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
        writeCommandOutputEnable = false
      )
    )
    // address input
    addrInst.io.slaveAid    <> IntSlavesAr(index).id
    addrInst.io.slaveAaddr  <> IntSlavesAr(index).addr
    addrInst.io.slaveAprot  <> IntSlavesAr(index).prot
    addrInst.io.slaveAqos   <> IntSlavesAr(index).qos
    addrInst.io.slaveAvalid <> IntSlavesAr(index).valid
    addrInst.io.slaveAready <> IntSlavesAr(index).ready
    // address output
    addrInst.io.masterAregion <> IntSlavesAr(index).region
    addrInst.io.masterSelect  <> aSelect
    addrInst.io.masterAvalid  <> masterAValid
    addrInst.io.masterAready  <> masterAReady
    // write command output
    addrInst.io.masterWriteCommandSelect <> DontCare
    addrInst.io.masterWriteCommandDecerr <> DontCare
    addrInst.io.masterWriteCommandValid  <> DontCare
    addrInst.io.masterWriteCommandReady  <> true.B
    // response command output
    addrInst.io.masterReplyCommandDecerr <> masterRcDecerr
    addrInst.io.masterReplyCommandValid  <> masterRcValid
    addrInst.io.masterReplyCommandReady  <> masterRcReady
    // completion input
    addrInst.io.slaveCompletionId    <> slaveCplId
    addrInst.io.slaveCompletionValid <> slaveCplValid

    IntArValid(index) := (masterAValid << aSelect)
    masterAReady      := IntArReady(aSelect)(index)

    // decode error handling
    val decerrMasterRidNext = Wire(UInt(slaveCount.W))
    val decerrMasterRidReg  = RegNext(decerrMasterRidNext)
    decerrMasterRidReg := 0.U
    val decerrMasterRlastNext = Wire(Bool())
    val decerrMasterRlastReg  = RegNext(decerrMasterRlastNext)
    decerrMasterRlastReg := false.B
    val decerrMasterRvalidNext = Wire(Bool())
    val decerrMasterRvalidReg  = RegNext(decerrMasterRvalidNext, false.B)
    decerrMasterRvalidReg := false.B
    val decerrMasterRready = Wire(Bool())

    val decerrLenNext = Wire(UInt(8.W))
    val decerrLenReg  = RegNext(decerrLenNext)
    decerrLenReg := 0.U

    masterRcReady := !decerrMasterRvalidReg

    decerrLenNext          := decerrLenReg
    decerrMasterRidNext    := decerrMasterRidReg
    decerrMasterRlastNext  := decerrMasterRlastReg
    decerrMasterRvalidNext := decerrMasterRvalidReg

    when(decerrMasterRvalidReg) {
      when(decerrMasterRready) {
        when(decerrLenReg > 0.U) {
          decerrLenNext          := decerrLenReg - 1.U
          decerrMasterRlastNext  := (decerrLenNext === 0.U)
          decerrMasterRvalidNext := true.B
        }.otherwise {
          decerrMasterRvalidNext := false.B
        }
      }
    }.elsewhen(masterRcValid && masterRcReady) {
      decerrLenNext          := IntSlavesAr(index).len
      decerrMasterRidNext    := IntSlavesAr(index).id
      decerrMasterRlastNext  := (decerrLenNext === 0.U)
      decerrMasterRvalidNext := true.B
    }

    // read response arbitration
    val rRequest      = Wire(Vec(masterCount + 1, Bool()))
    val rAcknowledge  = Wire(Vec(masterCount + 1, Bool()))
    val rGrant        = Wire(UInt((masterCount + 1).W))
    val rGrantValid   = Wire(Bool())
    val rGrantEncoded = Wire(UInt(log2Ceil(masterCount + 1).W))
    val rArbiter      = Module(new Arbiter(ports = masterCount + 1))
    rArbiter.io.request      <> rRequest.asUInt
    rArbiter.io.acknowledge  <> rAcknowledge.asUInt
    rArbiter.io.grant        <> rGrant
    rArbiter.io.grantValid   <> rGrantValid
    rArbiter.io.grantEncoded <> rGrantEncoded

    // read response mux
    val masterRidMux    = Wire(UInt(Param.Width.Axi.slaveId.W))
    val masterRdataMux  = Wire(UInt(Width.Axi.addr))
    val masterRrespMux  = Wire(UInt(2.W))
    val masterRlastMux  = Wire(Bool())
    val masterRuserMux  = Wire(UInt(Width.Axi.ruser))
    val masterRvalidMux = Wire(Bool())
    val masterRreadyMux = Wire(Bool())

    masterRidMux := IntMastersR.reverse.foldLeft(decerrMasterRidReg)((result, item) =>
      Cat(result, item.id)
    ) >> (rGrantEncoded * Param.Width.Axi.masterId.U)
    masterRdataMux := IntMastersR.reverse.foldLeft(0.U(Width.Axi.addr))((result, item) =>
      Cat(result, item.data)
    ) >> (rGrantEncoded * Width.Axi.data.get.U)
    masterRrespMux := IntMastersR.reverse.foldLeft(3.U(2.W))((result, item) =>
      Cat(result, item.resp)
    ) >> (rGrantEncoded * 2.U)
    masterRlastMux := IntMastersR.reverse.foldLeft(decerrMasterRlastReg.asUInt)((result, item) =>
      Cat(result, item.last)
    ) >> rGrantEncoded
    masterRuserMux := IntMastersR.reverse.foldLeft(0.U(Width.Axi.ruser))((result, item) =>
      Cat(result, item.user)
    ) >> (rGrantEncoded * Width.Axi.ruser.get.U)
    masterRvalidMux := (IntMastersR.reverse.foldLeft(decerrMasterRvalidReg.asUInt)((result, item) =>
      Cat(result, item.valid)
    ) >> rGrantEncoded) & rGrantValid

    IntRReady(index)   := (rGrantValid && masterRreadyMux) << rGrantEncoded
    decerrMasterRready := (rGrantValid && masterRreadyMux) && (rGrantEncoded === masterCount.U)

    for (n <- 0 until masterCount) {
      rRequest(n)     := IntRValid(n)(index) && !rGrant(n)
      rAcknowledge(n) := rGrant(n) && IntRValid(n)(index) && masterRlastMux && masterRreadyMux
    }

    rRequest(masterCount)     := decerrMasterRvalidReg && !rGrant(masterCount)
    rAcknowledge(masterCount) := rGrant(masterCount) && decerrMasterRvalidReg && decerrMasterRlastReg && masterRreadyMux

    slaveCplId    := masterRidMux
    slaveCplValid := masterRvalidMux && masterRreadyMux && masterRlastMux

    val regInst = Module(new AxiRegisterRead(arRegType = slaveArRegType(index), rRegType = slaveRRegType(index)))
    regInst.io.slave.ar.ready        <> io.slaves(index).ar.ready
    regInst.io.slave.ar.valid        <> io.slaves(index).ar.valid
    regInst.io.slave.ar.bits.id      <> io.slaves(index).ar.bits.id
    regInst.io.slave.ar.bits.addr    <> io.slaves(index).ar.bits.addr
    regInst.io.slave.ar.bits.len     <> io.slaves(index).ar.bits.len
    regInst.io.slave.ar.bits.size    <> io.slaves(index).ar.bits.size
    regInst.io.slave.ar.bits.burst   <> io.slaves(index).ar.bits.burst
    regInst.io.slave.ar.bits.lock    <> io.slaves(index).ar.bits.lock
    regInst.io.slave.ar.bits.cache   <> io.slaves(index).ar.bits.cache
    regInst.io.slave.ar.bits.prot    <> io.slaves(index).ar.bits.prot
    regInst.io.slave.ar.bits.qos     <> io.slaves(index).ar.bits.qos
    regInst.io.slave.ar.bits.region  <> 0.U
    regInst.io.slave.ar.bits.user    <> io.slaves(index).ar.bits.user
    regInst.io.slave.r               <> io.slaves(index).r
    regInst.io.master.ar.bits.id     <> IntSlavesAr(index).id
    regInst.io.master.ar.bits.addr   <> IntSlavesAr(index).addr
    regInst.io.master.ar.bits.len    <> IntSlavesAr(index).len
    regInst.io.master.ar.bits.size   <> IntSlavesAr(index).size
    regInst.io.master.ar.bits.burst  <> IntSlavesAr(index).burst
    regInst.io.master.ar.bits.lock   <> IntSlavesAr(index).lock
    regInst.io.master.ar.bits.cache  <> IntSlavesAr(index).cache
    regInst.io.master.ar.bits.prot   <> IntSlavesAr(index).prot
    regInst.io.master.ar.bits.qos    <> IntSlavesAr(index).qos
    regInst.io.master.ar.bits.region <> DontCare
    regInst.io.master.ar.bits.user   <> IntSlavesAr(index).user
    regInst.io.master.ar.valid       <> IntSlavesAr(index).valid
    regInst.io.master.ar.ready       <> IntSlavesAr(index).ready
    regInst.io.master.r.bits.id      <> masterRidMux
    regInst.io.master.r.bits.data    <> masterRdataMux
    regInst.io.master.r.bits.resp    <> masterRrespMux
    regInst.io.master.r.bits.last    <> masterRlastMux
    regInst.io.master.r.bits.user    <> masterRuserMux
    regInst.io.master.r.valid        <> masterRvalidMux
    regInst.io.master.r.ready        <> masterRreadyMux
  }

  for (index <- 0 until masterCount) {
    // in-flight transaction count
    val transStart    = Wire(Bool())
    val transComplete = Wire(Bool())
    val transCountReg = RegInit(0.U(log2Ceil(masterIssue(index) + 1).W))

    val transLimit = (transCountReg >= masterIssue(index).U) && !transComplete
    when(transStart && !transComplete) {
      transCountReg := transCountReg + 1.U
    }.elsewhen(!transStart && transComplete) {
      transCountReg := transCountReg - 1.U
    }

    val aRequest      = Wire(Vec(slaveCount, Bool()))
    val aAcknowledge  = Wire(Vec(slaveCount, Bool()))
    val aGrant        = Wire(UInt(slaveCount.W))
    val aGrantValid   = Wire(Bool())
    val aGrantEncoded = Wire(UInt(log2Ceil(slaveCount).W))

    val aArb = Module(new Arbiter(ports = slaveCount))
    aArb.io.request      <> aRequest.asUInt
    aArb.io.acknowledge  <> aAcknowledge.asUInt
    aArb.io.grant        <> aGrant
    aArb.io.grantValid   <> aGrantValid
    aArb.io.grantEncoded <> aGrantEncoded

    val slaveAridMux     = Wire(UInt(Param.Width.Axi.masterId.W))
    val slaveAraddrMux   = WireInit(IntSlavesAr(aGrantEncoded).addr)
    val slaveArlenMux    = WireInit(IntSlavesAr(aGrantEncoded).len)
    val slaveArsizeMux   = WireInit(IntSlavesAr(aGrantEncoded).size)
    val slaveArburstMux  = WireInit(IntSlavesAr(aGrantEncoded).burst)
    val slaveArlockMux   = WireInit(IntSlavesAr(aGrantEncoded).lock)
    val slaveArcacheMux  = WireInit(IntSlavesAr(aGrantEncoded).cache)
    val slaveArprotMux   = WireInit(IntSlavesAr(aGrantEncoded).prot)
    val slaveArqosMux    = WireInit(IntSlavesAr(aGrantEncoded).qos)
    val slaveArregionMux = WireInit(IntSlavesAr(aGrantEncoded).region)
    val slaveAruserMux   = WireInit(IntSlavesAr(aGrantEncoded).user)
    val slaveArvalidMux  = WireInit(IntArValid(aGrantEncoded)(index) && aGrantValid)
    val slaveArreadyMux  = Wire(Bool())
    slaveAridMux     := IntSlavesAr(aGrantEncoded).id | (aGrantEncoded << Param.Width.Axi.slaveId).asUInt

    IntArReady(index) := (aGrantValid && slaveArreadyMux) << aGrantEncoded

    for (m <- 0 until slaveCount) {
      aRequest(m)     := IntArValid(m)(index) && !aGrant(m) && !transLimit
      aAcknowledge(m) := aGrant(m) && IntArValid(m)(index) && slaveArreadyMux
    }

    transStart := slaveArvalidMux && slaveArreadyMux && aGrantValid

    // read response forwarding
    val rSelect = Wire(UInt(log2Ceil(slaveCount).W))
    rSelect := io.masters(index).r.bits.id >> Param.Width.Axi.slaveId.U

    IntRValid(index)         := IntMastersR(index).valid << rSelect
    IntMastersR(index).ready := IntRReady(rSelect)(index)

    transComplete := IntMastersR(index).valid && IntMastersR(index).ready

    // M side register
    val regInst = Module(new AxiRegisterRead(arRegType = masterArRegType(index), rRegType = masterRRegType(index)))
    regInst.io.slave.ar.bits.id     <> slaveAridMux
    regInst.io.slave.ar.bits.addr   <> slaveAraddrMux
    regInst.io.slave.ar.bits.len    <> slaveArlenMux
    regInst.io.slave.ar.bits.size   <> slaveArsizeMux
    regInst.io.slave.ar.bits.burst  <> slaveArburstMux
    regInst.io.slave.ar.bits.lock   <> slaveArlockMux
    regInst.io.slave.ar.bits.cache  <> slaveArcacheMux
    regInst.io.slave.ar.bits.prot   <> slaveArprotMux
    regInst.io.slave.ar.bits.qos    <> slaveArqosMux
    regInst.io.slave.ar.bits.region <> slaveArregionMux
    regInst.io.slave.ar.bits.user   <> slaveAruserMux
    regInst.io.slave.ar.valid       <> slaveArvalidMux
    regInst.io.slave.ar.ready       <> slaveArreadyMux
    regInst.io.slave.r.bits.id      <> IntMastersR(index).id
    regInst.io.slave.r.bits.data    <> IntMastersR(index).data
    regInst.io.slave.r.bits.resp    <> IntMastersR(index).resp
    regInst.io.slave.r.bits.last    <> IntMastersR(index).last
    regInst.io.slave.r.bits.user    <> IntMastersR(index).user
    regInst.io.slave.r.valid        <> IntMastersR(index).valid
    regInst.io.slave.r.ready        <> IntMastersR(index).ready
    regInst.io.master               <> io.masters(index)
  }
}
