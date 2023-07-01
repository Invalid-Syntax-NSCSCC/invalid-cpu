
//  Xilinx True Dual Port RAM Read First Single Clock
//  This code implements a parameterizable true dual port memory (both ports can read and write).
//  The behavior of this RAM is when data is written, the prior memory contents at the write
//  address are presented on the output port.  If the output data is
//  not needed during writes or the last read value is desired to be retained,
//  it is suggested to use a no change RAM as it is more power efficient.
//  If a reset or enable is not necessary, it may be tied off or removed from the code.
module truedual_readfirst_bram #(
  parameter RAM_WIDTH = 88,                       // Specify RAM data width
  parameter RAM_DEPTH = 1024,                     // Specify RAM depth (number of entries)
  parameter RAM_PERFORMANCE = "HIGH_PERFORMANCE", // Select "HIGH_PERFORMANCE" or "LOW_LATENCY"
  parameter INIT_FILE = ""                        // Specify name/location of RAM initialization file if using one (leave blank if not)
)(
  input  wire [clogb2(RAM_DEPTH-1)-1:0] addra,  // Port A address bus, width determined from RAM_DEPTH
  input  wire [clogb2(RAM_DEPTH-1)-1:0] addrb,  // Port B address bus, width determined from RAM_DEPTH
  input  wire [RAM_WIDTH-1:0] dina,             // Port A RAM input data
  input  wire [RAM_WIDTH-1:0] dinb,             // Port B RAM input data
  input  wire clka,                             // Clock
  input  wire wea,                              // Port A write enable
  input  wire web,                              // Port B write enable
  input  wire ena,                              // Port A RAM Enable, for additional power savings, disable port when not in use
  input  wire enb,                              // Port B RAM Enable, for additional power savings, disable port when not in use
  input  wire rsta,                             // Port A output reset (does not affect memory contents)
  input  wire rstb,                             // Port B output reset (does not affect memory contents)
  input  wire regcea,                           // Port A output register enable
  input  wire regceb,                           // Port B output register enable
  output wire [RAM_WIDTH-1:0] douta,            // Port A RAM output data
  output wire [RAM_WIDTH-1:0] doutb             // Port B RAM output data
);

  reg [RAM_WIDTH-1:0] ram_name [RAM_DEPTH-1:0];
  reg [RAM_WIDTH-1:0] ram_data_a = {RAM_WIDTH{1'b0}};
  reg [RAM_WIDTH-1:0] ram_data_b = {RAM_WIDTH{1'b0}};

  // The following code either initializes the memory values to a specified file or to all zeros to match hardware
  generate
    if (INIT_FILE != "") begin: use_init_file
      initial
        $readmemh(INIT_FILE, ram_name, 0, RAM_DEPTH-1);
    end else begin: init_bram_to_zero
      integer ram_index;
      initial
        for (ram_index = 0; ram_index < RAM_DEPTH; ram_index = ram_index + 1)
          ram_name[ram_index] = {RAM_WIDTH{1'b0}};
    end
  endgenerate

  always @(posedge clka)
    if (ena) begin
      if (wea)
        ram_name[addra] <= dina;
      ram_data_a <= ram_name[addra];
    end

  always @(posedge clka)
    if (enb) begin
      if (web)
        ram_name[addrb] <= dinb;
      ram_data_b <= ram_name[addrb];
    end

  //  The following code generates HIGH_PERFORMANCE (use output register) or LOW_LATENCY (no output register)
  generate
    if (RAM_PERFORMANCE == "LOW_LATENCY") begin: no_output_register

      // The following is a 1 clock cycle read latency at the cost of a longer clock-to-out timing
       assign douta = ram_data_a;
       assign doutb = ram_data_b;

    end else begin: output_register

      // The following is a 2 clock cycle read latency with improve clock-to-out timing

      reg [RAM_WIDTH-1:0] douta_reg = {RAM_WIDTH{1'b0}};
      reg [RAM_WIDTH-1:0] doutb_reg = {RAM_WIDTH{1'b0}};

      always @(posedge clka)
        if (rsta)
          douta_reg <= {RAM_WIDTH{1'b0}};
        else if (regcea)
          douta_reg <= ram_data_a;

      always @(posedge clka)
        if (rstb)
          doutb_reg <= {RAM_WIDTH{1'b0}};
        else if (regceb)
          doutb_reg <= ram_data_b;

      assign douta = douta_reg;
      assign doutb = doutb_reg;

    end
  endgenerate

  //  The following function calculates the address width based on specified RAM depth
  function integer clogb2;
    input integer depth;
      for (clogb2=0; depth > 0; clogb2=clogb2+1)
        depth = depth >> 1;
  endfunction
							
endmodule
