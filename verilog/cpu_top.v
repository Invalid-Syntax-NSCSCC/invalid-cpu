// `include "CoreCpuTop.v"
parameter AXI_DATA_WIDTH = 128;

module core_top (
    input aclk,
    input aresetn,

    input [7:0] intrpt,  // External interrupt

    // AXI interface
    // read request
    output       [                   3:0] arid,
    output       [                  31:0] araddr,
    output       [                   7:0] arlen,
    output       [                   2:0] arsize,
    output       [                   1:0] arburst,
    output       [                   1:0] arlock,
    output       [                   3:0] arcache,
    output       [                   2:0] arprot,
    output                                arvalid,
    input                                 arready,
    // read back
    input        [                   3:0] rid,
    input        [    AXI_DATA_WIDTH-1:0] rdata,
    input        [                   1:0] rresp,
    input                                 rlast,
    input                                 rvalid,
    output                                rready,
    // write request
    output       [                   3:0] awid,
    output       [                  31:0] awaddr,
    output       [                   7:0] awlen,
    output       [                   2:0] awsize,
    output       [                   1:0] awburst,
    output       [                   1:0] awlock,
    output       [                   3:0] awcache,
    output       [                   2:0] awprot,
    output                                awvalid,
    input                                 awready,
    // write data
    output reg   [                   3:0] wid,
    output       [    AXI_DATA_WIDTH-1:0] wdata,
    output       [(AXI_DATA_WIDTH/8)-1:0] wstrb,
    output                                wlast,
    output                                wvalid,
    input                                 wready,
    // write back
    input        [                   3:0] bid,
    input        [                   1:0] bresp,
    input                                 bvalid,
    output                                bready,
    // debug info
    output       [                  31:0] debug0_wb_pc,
    output       [                   3:0] debug0_wb_rf_wen,
    output       [                   4:0] debug0_wb_rf_wnum,
    output       [                  31:0] debug0_wb_rf_wdata,
    output       [                  31:0] debug0_wb_inst
);

`ifdef DIFFTEST_EN
    // Wires
    wire             cmt_valid           ;
    wire             cmt_cnt_inst        ;
    wire     [63:0]  cmt_timer_64        ;
    wire     [ 7:0]  cmt_inst_ld_en      ;
    wire     [31:0]  cmt_ld_paddr        ;
    wire     [31:0]  cmt_ld_vaddr        ;
    wire     [ 7:0]  cmt_inst_st_en      ;
    wire     [31:0]  cmt_st_paddr        ;
    wire     [31:0]  cmt_st_vaddr        ;
    wire     [31:0]  cmt_st_data         ;
    wire             cmt_csr_rstat_en    ;
    wire     [31:0]  cmt_csr_data        ;

    wire             cmt_wen             ;
    wire     [ 7:0]  cmt_wdest           ;
    wire     [31:0]  cmt_wdata           ;
    wire     [31:0]  cmt_pc              ;
    wire     [31:0]  cmt_inst            ;

    wire             cmt_excp_flush      ;
    wire             cmt_ertn            ;
    wire     [5:0]   cmt_csr_ecode       ;
    wire             cmt_tlbfill_en      ;
    wire     [4:0]   cmt_rand_index      ;

    wire    [31:0]  csr_crmd_diff_0     ;
    wire    [31:0]  csr_prmd_diff_0     ;
    wire    [31:0]  csr_ectl_diff_0     ;
    wire    [31:0]  csr_estat_diff_0    ;
    wire    [31:0]  csr_era_diff_0      ;
    wire    [31:0]  csr_badv_diff_0     ;
    wire    [31:0]  csr_eentry_diff_0   ;
    wire    [31:0]  csr_tlbidx_diff_0   ;
    wire    [31:0]  csr_tlbehi_diff_0   ;
    wire    [31:0]  csr_tlbelo0_diff_0  ;
    wire    [31:0]  csr_tlbelo1_diff_0  ;
    wire    [31:0]  csr_asid_diff_0     ;
    wire    [31:0]  csr_save0_diff_0    ;
    wire    [31:0]  csr_save1_diff_0    ;
    wire    [31:0]  csr_save2_diff_0    ;
    wire    [31:0]  csr_save3_diff_0    ;
    wire    [31:0]  csr_tid_diff_0      ;
    wire    [31:0]  csr_tcfg_diff_0     ;
    wire    [31:0]  csr_tval_diff_0     ;
    wire    [31:0]  csr_ticlr_diff_0    ;
    wire    [31:0]  csr_llbctl_diff_0   ;
    wire    [31:0]  csr_tlbrentry_diff_0;
    wire    [31:0]  csr_dmw0_diff_0     ;
    wire    [31:0]  csr_dmw1_diff_0     ;
    wire    [31:0]  csr_pgdl_diff_0     ;
    wire    [31:0]  csr_pgdh_diff_0     ;

    wire    [31:0]  regs[31:0]          ;
`endif

    CoreCpuTop coreCpuTop (
        .clock(aclk),
        .reset(!aresetn),
        .io_intrpt(intrpt),
        .io_axi_arready(arready),
        .io_axi_rid(rid),
        .io_axi_rdata(rdata),
        .io_axi_rresp(rresp),
        .io_axi_rlast(rlast),
        .io_axi_rvalid(rvalid),
        .io_axi_awready(awready),
        .io_axi_wready(wready),
        .io_axi_bid(bid),
        .io_axi_bresp(bresp),
        .io_axi_bvalid(bvalid),
        .io_axi_arid(arid),
        .io_axi_araddr(araddr),
        .io_axi_arlen(arlen),
        .io_axi_arsize(arsize),
        .io_axi_arburst(arburst),
        .io_axi_arlock(arlock),
        .io_axi_arcache(arcache),
        .io_axi_arprot(arprot),
        .io_axi_arvalid(arvalid),
        .io_axi_rready(rready),
        .io_axi_awid(awid),
        .io_axi_awaddr(awaddr),
        .io_axi_awlen(awlen),
        .io_axi_awsize(awsize),
        .io_axi_awburst(awburst),
        .io_axi_awlock(awlock),
        .io_axi_awcache(awcache),
        .io_axi_awprot(awprot),
        .io_axi_awvalid(awvalid),
        .io_axi_wid(wid),
        .io_axi_wdata(wdata),
        .io_axi_wstrb(wstrb),
        .io_axi_wlast(wlast),
        .io_axi_wvalid(wvalid),
        .io_axi_bready(bready),
        .io_debug0_wb_pc(debug0_wb_pc),
        .io_debug0_wb_rf_wen(debug0_wb_rf_wen),
        .io_debug0_wb_rf_wnum(debug0_wb_rf_wnum),
        .io_debug0_wb_rf_wdata(debug0_wb_rf_wdata),
        .io_debug0_wb_inst(debug0_wb_inst),
        .io_diffTest_cmt_valid(cmt_valid),
        .io_diffTest_cmt_cnt_inst(cmt_cnt_inst),
        .io_diffTest_cmt_timer_64(cmt_timer_64),
        .io_diffTest_cmt_inst_ld_en(cmt_inst_ld_en),
        .io_diffTest_cmt_ld_paddr(cmt_ld_paddr),
        .io_diffTest_cmt_ld_vaddr(cmt_ld_vaddr),
        .io_diffTest_cmt_inst_st_en(cmt_inst_st_en),
        .io_diffTest_cmt_st_paddr(cmt_st_paddr),
        .io_diffTest_cmt_st_vaddr(cmt_st_vaddr),
        .io_diffTest_cmt_st_data(cmt_st_data),
        .io_diffTest_cmt_csr_rstat_en(cmt_csr_rstat_en),
        .io_diffTest_cmt_csr_data(cmt_csr_data),
        .io_diffTest_cmt_wen(cmt_wen),
        .io_diffTest_cmt_wdest(cmt_wdest),
        .io_diffTest_cmt_wdata(cmt_wdata),
        .io_diffTest_cmt_pc(cmt_pc),
        .io_diffTest_cmt_inst(cmt_inst),
        .io_diffTest_cmt_excp_flush(cmt_excp_flush),
        .io_diffTest_cmt_ertn(cmt_ertn),
        .io_diffTest_cmt_csr_ecode(cmt_csr_ecode),
        .io_diffTest_cmt_tlbfill_en(cmt_tlbfill_en),
        .io_diffTest_cmt_rand_index(cmt_rand_index),
        .io_diffTest_csr_crmd_diff_0(csr_crmd_diff_0),
        .io_diffTest_csr_prmd_diff_0(csr_prmd_diff_0),
        .io_diffTest_csr_ectl_diff_0(csr_ectl_diff_0),
        .io_diffTest_csr_estat_diff_0(csr_estat_diff_0),
        .io_diffTest_csr_era_diff_0(csr_era_diff_0),
        .io_diffTest_csr_badv_diff_0(csr_badv_diff_0),
        .io_diffTest_csr_eentry_diff_0(csr_eentry_diff_0),
        .io_diffTest_csr_tlbidx_diff_0(csr_tlbidx_diff_0),
        .io_diffTest_csr_tlbehi_diff_0(csr_tlbehi_diff_0),
        .io_diffTest_csr_tlbelo0_diff_0(csr_tlbelo0_diff_0),
        .io_diffTest_csr_tlbelo1_diff_0(csr_tlbelo1_diff_0),
        .io_diffTest_csr_asid_diff_0(csr_asid_diff_0),
        .io_diffTest_csr_save0_diff_0(csr_save0_diff_0),
        .io_diffTest_csr_save1_diff_0(csr_save1_diff_0),
        .io_diffTest_csr_save2_diff_0(csr_save2_diff_0),
        .io_diffTest_csr_save3_diff_0(csr_save3_diff_0),
        .io_diffTest_csr_tid_diff_0(csr_tid_diff_0),
        .io_diffTest_csr_tcfg_diff_0(csr_tcfg_diff_0),
        .io_diffTest_csr_tval_diff_0(csr_tval_diff_0),
        .io_diffTest_csr_ticlr_diff_0(csr_ticlr_diff_0),
        .io_diffTest_csr_llbctl_diff_0(csr_llbctl_diff_0),
        .io_diffTest_csr_tlbrentry_diff_0(csr_tlbrentry_diff_0),
        .io_diffTest_csr_dmw0_diff_0(csr_dmw0_diff_0),
        .io_diffTest_csr_dmw1_diff_0(csr_dmw1_diff_0),
        .io_diffTest_csr_pgdl_diff_0(csr_pgdl_diff_0),
        .io_diffTest_csr_pgdh_diff_0(csr_pgdh_diff_0),
        .io_diffTest_regs_0(regs[0]),
        .io_diffTest_regs_1(regs[1]),
        .io_diffTest_regs_2(regs[2]),
        .io_diffTest_regs_3(regs[3]),
        .io_diffTest_regs_4(regs[4]),
        .io_diffTest_regs_5(regs[5]),
        .io_diffTest_regs_6(regs[6]),
        .io_diffTest_regs_7(regs[7]),
        .io_diffTest_regs_8(regs[8]),
        .io_diffTest_regs_9(regs[9]),
        .io_diffTest_regs_10(regs[10]),
        .io_diffTest_regs_11(regs[11]),
        .io_diffTest_regs_12(regs[12]),
        .io_diffTest_regs_13(regs[13]),
        .io_diffTest_regs_14(regs[14]),
        .io_diffTest_regs_15(regs[15]),
        .io_diffTest_regs_16(regs[16]),
        .io_diffTest_regs_17(regs[17]),
        .io_diffTest_regs_18(regs[18]),
        .io_diffTest_regs_19(regs[19]),
        .io_diffTest_regs_20(regs[20]),
        .io_diffTest_regs_21(regs[21]),
        .io_diffTest_regs_22(regs[22]),
        .io_diffTest_regs_23(regs[23]),
        .io_diffTest_regs_24(regs[24]),
        .io_diffTest_regs_25(regs[25]),
        .io_diffTest_regs_26(regs[26]),
        .io_diffTest_regs_27(regs[27]),
        .io_diffTest_regs_28(regs[28]),
        .io_diffTest_regs_29(regs[29]),
        .io_diffTest_regs_30(regs[30]),
        .io_diffTest_regs_31(regs[31])
    );

`ifdef DIFFTEST_EN
    DifftestInstrCommit DifftestInstrCommit(
        .clock              (aclk           ),
        .coreid             (0              ),
        .index              (0              ),
        .valid              (cmt_valid      ),
        .pc                 (cmt_pc         ),
        .instr              (cmt_inst       ),
        .skip               (0              ),
        .is_TLBFILL         (cmt_tlbfill_en ),
        .TLBFILL_index      (cmt_rand_index ),
        .is_CNTinst         (cmt_cnt_inst   ),
        .timer_64_value     (cmt_timer_64   ),
        .wen                (cmt_wen        ),
        .wdest              (cmt_wdest      ),
        .wdata              (cmt_wdata      ),
        .csr_rstat          (cmt_csr_rstat_en),
        .csr_data           (cmt_csr_data   )
    );

    DifftestExcpEvent DifftestExcpEvent(
        .clock              (aclk           ),
        .coreid             (0              ),
        .excp_valid         (cmt_excp_flush ),
        .eret               (cmt_ertn       ),
        .intrNo             (csr_estat_diff_0[12:2]),
        .cause              (cmt_csr_ecode  ),
        .exceptionPC        (cmt_pc         ),
        .exceptionInst      (cmt_inst       )
    );
/*
    DifftestTrapEvent DifftestTrapEvent(
        .clock              (aclk           ),
        .coreid             (0              ),
        .valid              (trap           ),
        .code               (trap_code      ),
        .pc                 (cmt_pc         ),
        .cycleCnt           (cycleCnt       ),
        .instrCnt           (instrCnt       )
    );
*/
    DifftestStoreEvent DifftestStoreEvent(
        .clock              (aclk           ),
        .coreid             (0              ),
        .index              (0              ),
        .valid              (cmt_inst_st_en ),
        .storePAddr         (cmt_st_paddr   ),
        .storeVAddr         (cmt_st_vaddr   ),
        .storeData          (cmt_st_data    )
    );

    DifftestLoadEvent DifftestLoadEvent(
        .clock              (aclk           ),
        .coreid             (0              ),
        .index              (0              ),
        .valid              (cmt_inst_ld_en ),
        .paddr              (cmt_ld_paddr   ),
        .vaddr              (cmt_ld_vaddr   )
    );

    DifftestCSRRegState DifftestCSRRegState(
        .clock              (aclk               ),
        .coreid             (0                  ),
        .crmd               (csr_crmd_diff_0    ),
        .prmd               (csr_prmd_diff_0    ),
        .euen               (0                  ),
        .ecfg               (csr_ectl_diff_0    ),
        .estat              (csr_estat_diff_0   ),
        .era                (csr_era_diff_0     ),
        .badv               (csr_badv_diff_0    ),
        .eentry             (csr_eentry_diff_0  ),
        .tlbidx             (csr_tlbidx_diff_0  ),
        .tlbehi             (csr_tlbehi_diff_0  ),
        .tlbelo0            (csr_tlbelo0_diff_0 ),
        .tlbelo1            (csr_tlbelo1_diff_0 ),
        .asid               (csr_asid_diff_0    ),
        .pgdl               (csr_pgdl_diff_0    ),
        .pgdh               (csr_pgdh_diff_0    ),
        .save0              (csr_save0_diff_0   ),
        .save1              (csr_save1_diff_0   ),
        .save2              (csr_save2_diff_0   ),
        .save3              (csr_save3_diff_0   ),
        .tid                (csr_tid_diff_0     ),
        .tcfg               (csr_tcfg_diff_0    ),
        .tval               (csr_tval_diff_0    ),
        .ticlr              (csr_ticlr_diff_0   ),
        .llbctl             (csr_llbctl_diff_0  ),
        .tlbrentry          (csr_tlbrentry_diff_0),
        .dmw0               (csr_dmw0_diff_0    ),
        .dmw1               (csr_dmw1_diff_0    )
    );

    DifftestGRegState DifftestGRegState(
        .clock              (aclk       ),
        .coreid             (0          ),
        .gpr_0              (0          ),
        .gpr_1              (regs[1]    ),
        .gpr_2              (regs[2]    ),
        .gpr_3              (regs[3]    ),
        .gpr_4              (regs[4]    ),
        .gpr_5              (regs[5]    ),
        .gpr_6              (regs[6]    ),
        .gpr_7              (regs[7]    ),
        .gpr_8              (regs[8]    ),
        .gpr_9              (regs[9]    ),
        .gpr_10             (regs[10]   ),
        .gpr_11             (regs[11]   ),
        .gpr_12             (regs[12]   ),
        .gpr_13             (regs[13]   ),
        .gpr_14             (regs[14]   ),
        .gpr_15             (regs[15]   ),
        .gpr_16             (regs[16]   ),
        .gpr_17             (regs[17]   ),
        .gpr_18             (regs[18]   ),
        .gpr_19             (regs[19]   ),
        .gpr_20             (regs[20]   ),
        .gpr_21             (regs[21]   ),
        .gpr_22             (regs[22]   ),
        .gpr_23             (regs[23]   ),
        .gpr_24             (regs[24]   ),
        .gpr_25             (regs[25]   ),
        .gpr_26             (regs[26]   ),
        .gpr_27             (regs[27]   ),
        .gpr_28             (regs[28]   ),
        .gpr_29             (regs[29]   ),
        .gpr_30             (regs[30]   ),
        .gpr_31             (regs[31]   )
    );

`endif

endmodule
