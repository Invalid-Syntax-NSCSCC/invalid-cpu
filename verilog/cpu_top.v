`include "CoreCpuTop.v"

module cpu_top (
    input logic aclk,
    input logic aresetn,

    input logic [7:0] intrpt,  // External interrupt

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
    output logic [                  31:0] debug0_wb_pc,
    output logic [                   3:0] debug0_wb_rf_wen,
    output logic [                   4:0] debug0_wb_rf_wnum,
    output logic [                  31:0] debug0_wb_rf_wdata
);

    CoreCpuTop coreCpuTop (
        .clock(aclk),
        .reset(!aresetn),
        .intrpt(intrpt),
        .axi_arready(arready),
        .axi_rid(rid),
        .axi_rdata(rdata),
        .axi_rresp(rresp),
        .axi_rlast(rlast),
        .axi_rvalid(rvalid),
        .axi_awready(awready),
        .axi_wready(wready),
        .axi_bid(bid),
        .axi_bresp(bresp),
        .axi_bvalid(bvalid),
        .axi_arid(arid),
        .axi_araddr(araddr),
        .axi_arlen(arlen),
        .axi_arsize(arsize),
        .axi_arburst(arburst),
        .axi_arlock(arlock),
        .axi_arcache(arcache),
        .axi_arprot(arprot),
        .axi_arvalid(arvalid),
        .axi_rready(rready),
        .axi_awid(awid),
        .axi_awaddr(awaddr),
        .axi_awlen(awlen),
        .axi_awsize(awsize),
        .axi_awburst(awburst),
        .axi_awlock(awlock),
        .axi_awcache(awcache),
        .axi_awprot(awprot),
        .axi_awvalid(awvalid),
        .axi_wid(wid),
        .axi_wdata(wdata),
        .axi_wstrb(wstrb),
        .axi_wlast(wlast),
        .axi_wvalid(wvalid),
        .axi_bready(bready),
        .debug0_wb_pc(debug0_wb_pc),
        .debug0_wb_rf_wen(debug0_wb_rf_wen),
        .debug0_wb_rf_wnum(debug0_wb_rf_wnum),
        .debug0_wb_rf_wdata(debug0_wb_rf_wdata),
        .debug0_wb_inst(debug0_wb_inst)
    );

endmodule
