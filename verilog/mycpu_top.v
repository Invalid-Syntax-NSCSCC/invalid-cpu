`ifndef DIFFTEST_EN
`define AXI_DATA_WIDTH 32

module mycpu_top (
    input aclk,
    input aresetn,

    input [7:0] ext_int,  // External interrupt

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
    input        [   `AXI_DATA_WIDTH-1:0] rdata,
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
    output       [                   3:0] wid,
    output       [   `AXI_DATA_WIDTH-1:0] wdata,
    output       [(`AXI_DATA_WIDTH/8)-1:0] wstrb,
    output                                wlast,
    output                                wvalid,
    input                                 wready,
    // write back
    input        [                   3:0] bid,
    input        [                   1:0] bresp,
    input                                 bvalid,
    output                                bready,

    //debug
    input                                 break_point,
    input                                 infor_flag,
    input        [                   4:0] reg_num,
    output                                ws_valid,
    output       [                  31:0] rf_rdata,

    // debug info
    output       [                  31:0] debug_wb_pc,
    output       [                   3:0] debug_wb_rf_we,
    output       [                   3:0] debug_wb_rf_wen,
    output       [                   4:0] debug_wb_rf_wnum,
    output       [                  31:0] debug_wb_rf_wdata
);

    // Fit with AXI 3
    reg [3:0] awid_last;
    always @ (posedge aclk) begin
        if (~aresetn) begin
            awid_last <= 4'b0;
        end else if (awvalid) begin
            awid_last <= awid;
        end else begin
            awid_last <= awid_last;
        end
    end
    assign wid = awvalid ? awid : awid_last;

    assign debug_wb_rf_we = debug_wb_rf_wen;
    CoreCpuTop coreCpuTop (
        .clock                  (aclk       ),
        .reset                  (!aresetn   ),
        .io_intrpt              (ext_int    ),
        .io_axi_ar_ready        (arready    ),
        .io_axi_r_valid         (rvalid     ),
        .io_axi_r_bits_id       (rid        ),
        .io_axi_r_bits_data     (rdata      ),
        .io_axi_r_bits_resp     (rresp      ),
        .io_axi_r_bits_last     (rlast      ),
        .io_axi_aw_ready        (awready    ),
        .io_axi_w_ready         (wready     ),
        .io_axi_b_valid         (bvalid     ),
        .io_axi_b_bits_id       (bid        ),
        .io_axi_b_bits_resp     (bresp      ),
        .io_axi_ar_valid        (arvalid    ),
        .io_axi_ar_bits_id      (arid       ),
        .io_axi_ar_bits_addr    (araddr     ),
        .io_axi_ar_bits_len     (arlen      ),
        .io_axi_ar_bits_size    (arsize     ),
        .io_axi_ar_bits_burst   (arburst    ),
        .io_axi_ar_bits_lock    (arlock     ),
        .io_axi_ar_bits_cache   (arcache    ),
        .io_axi_ar_bits_prot    (arprot     ),
        .io_axi_ar_bits_qos     (           ),
        .io_axi_ar_bits_region  (           ),
        .io_axi_r_ready         (rready     ),
        .io_axi_aw_valid        (awvalid    ),
        .io_axi_aw_bits_id      (awid       ),
        .io_axi_aw_bits_addr    (awaddr     ),
        .io_axi_aw_bits_len     (awlen      ),
        .io_axi_aw_bits_size    (awsize     ),
        .io_axi_aw_bits_burst   (awburst    ),
        .io_axi_aw_bits_lock    (awlock     ),
        .io_axi_aw_bits_cache   (awcache    ),
        .io_axi_aw_bits_prot    (awprot     ),
        .io_axi_aw_bits_qos     (           ),
        .io_axi_aw_bits_region  (           ),
        .io_axi_w_valid         (wvalid     ),
        .io_axi_w_bits_data     (wdata      ),
        .io_axi_w_bits_strb     (wstrb      ),
        .io_axi_w_bits_last     (wlast      ),
        .io_axi_b_ready         (bready     ),
        .io_debug0_wb_pc(debug_wb_pc),
        .io_debug0_wb_rf_wen(debug_wb_rf_wen),
        .io_debug0_wb_rf_wnum(debug_wb_rf_wnum),
        .io_debug0_wb_rf_wdata(debug_wb_rf_wdata),
        .io_debug0_wb_inst()
    );

endmodule
`endif