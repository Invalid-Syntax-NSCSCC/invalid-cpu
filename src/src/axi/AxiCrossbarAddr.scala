package axi

import chisel3._
import chisel3.util._
import spec._

class AxiCrossbarAddr(
  val slaveIndex:               Int,
  val idWidth:                  Int,
  val slaveThread:              Int,
  val slaveAccept:              Int,
  val masterBaseAddr:           Int, // set to zero for default addressing
  val writeCommandOutputEnable: Boolean)
    extends Module {
  val io = IO(new Bundle {
    // address input
    val slaveAid    = Input(UInt(idWidth.W))
    val slaveAaddr  = Input(UInt(Width.Axi.addr.W))
    val slaveAprot  = Input(UInt(3.W))
    val slaveAqos   = Input(UInt(4.W))
    val slaveAvalid = Input(Bool())
    val slaveAready = Output(Bool())

    // address output
    val masterAregion = Output(UInt(3.W))
    val masterSelect  = Output(UInt(log2Ceil(Param.Count.Axi.master).W))
    val masterAvalid  = Output(Bool())
    val masterAready  = Input(Bool())

    // write command output
    val masterWriteCommandSelect = Output(UInt(log2Ceil(Param.Count.Axi.master).W))
    val masterWriteCommandDecerr = Output(Bool())
    val masterWriteCommandValid  = Output(Bool())
    val masterWriteCommandReady  = Input(Bool())

    // reply command output
    val masterReplyCommandDecerr = Output(Bool())
    val masterReplyCommandValid  = Output(Bool())
    val masterReplyCommandReady  = Input(Bool())

    // completion input
    val slaveCompletionId    = Input(UInt(idWidth.W))
    val slaveCompletionValid = Input(Bool())
  })

  val rawModule = Module(
    new axi_crossbar_addr(
      slaveIndex               = slaveIndex,
      idWidth                  = idWidth,
      slaveThread              = slaveThread,
      slaveAccept              = slaveAccept,
      masterBaseAddr           = masterBaseAddr,
      writeCommandOutputEnable = writeCommandOutputEnable
    )
  )

  rawModule.io.clk := clock
  rawModule.io.rst := reset
  rawModule.io.s_axi_aid <> io.slaveAid
  rawModule.io.s_axi_aaddr <> io.slaveAaddr
  rawModule.io.s_axi_aprot <> io.slaveAprot
  rawModule.io.s_axi_aqos <> io.slaveAqos
  rawModule.io.s_axi_avalid <> io.slaveAvalid
  rawModule.io.s_axi_aready <> io.slaveAready
  rawModule.io.m_axi_aregion <> io.masterAregion
  rawModule.io.m_select <> io.masterSelect
  rawModule.io.m_axi_avalid <> io.masterAvalid
  rawModule.io.m_axi_aready <> io.masterAready
  rawModule.io.m_wc_select <> io.masterWriteCommandSelect
  rawModule.io.m_wc_decerr <> io.masterWriteCommandDecerr
  rawModule.io.m_wc_valid <> io.masterWriteCommandValid
  rawModule.io.m_wc_ready <> io.masterWriteCommandReady
  rawModule.io.m_rc_decerr <> io.masterReplyCommandDecerr
  rawModule.io.m_rc_valid <> io.masterReplyCommandValid
  rawModule.io.m_rc_ready <> io.masterReplyCommandReady
  rawModule.io.s_cpl_id <> io.slaveCompletionId
  rawModule.io.s_cpl_valid <> io.slaveCompletionValid
}

class axi_crossbar_addr(
  val slaveIndex:               Int,
  val idWidth:                  Int,
  val slaveThread:              Int,
  val slaveAccept:              Int,
  val masterBaseAddr:           Int, // set to zero for default addressing
  val writeCommandOutputEnable: Boolean)
    extends BlackBox(
      Map(
        "S" -> slaveIndex,
        "S_COUNT" -> Param.Count.Axi.slave,
        "M_COUNT" -> Param.Count.Axi.master,
        "ADDR_WIDTH" -> Width.Axi.addr,
        "ID_WIDTH" -> idWidth,
        "S_THREADS" -> slaveThread,
        "S_ACCEPT" -> slaveAccept,
        "M_REGIONS" -> 1,
        "M_BASE_ADDR" -> masterBaseAddr,
        "WC_OUTPUT" -> (if (writeCommandOutputEnable) 1 else 0)
      )
    )
    with HasBlackBoxPath {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())

    // address input
    val s_axi_aid    = Input(UInt(idWidth.W))
    val s_axi_aaddr  = Input(UInt(Width.Axi.addr.W))
    val s_axi_aprot  = Input(UInt(3.W))
    val s_axi_aqos   = Input(UInt(4.W))
    val s_axi_avalid = Input(Bool())
    val s_axi_aready = Output(Bool())

    // address output
    val m_axi_aregion = Output(UInt(3.W))
    val m_select      = Output(UInt(log2Ceil(Param.Count.Axi.master).W))
    val m_axi_avalid  = Output(Bool())
    val m_axi_aready  = Input(Bool())

    // write command output
    val m_wc_select = Output(UInt(log2Ceil(Param.Count.Axi.master).W))
    val m_wc_decerr = Output(Bool())
    val m_wc_valid  = Output(Bool())
    val m_wc_ready  = Input(Bool())

    // reply command output
    val m_rc_decerr = Output(Bool())
    val m_rc_valid  = Output(Bool())
    val m_rc_ready  = Input(Bool())

    // completion input
    val s_cpl_id    = Input(UInt(idWidth.W))
    val s_cpl_valid = Input(Bool())
  })

  addPath("./src/src/axi/vsrc/axi_crossbar_addr.v")
}
