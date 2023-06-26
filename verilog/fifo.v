// fifo is a module to encapsule a lutram based FIFO

module fifo #(
    parameter DATA_WIDTH = 128,
    parameter DEPTH = 8
) (
    input clk,
    input rst,

    // Input
    input push,
    input [DATA_WIDTH-1:0] push_data,

    // Output
    input pop,
    output [DATA_WIDTH-1:0] pop_data,

    // Controll signals
    input  reset,  // Have reset
    output full,
    output empty
);

    // Parameters
    localparam PTR_WIDTH = $clog2(DEPTH);

    // Data structure
    (* ram_style = "distributed" *) reg [DATA_WIDTH-1:0] ram[DEPTH];

    reg [PTR_WIDTH-1:0] write_index;
    reg [PTR_WIDTH-1:0] read_index;


    // RAM operation
    always_ff @(posedge clk) begin
        // if (rst | reset) ram <= '{default: '0};
        // else 
        if (push) ram[write_index] <= push_data;
    end

    // PTR operation
    always_ff @(posedge clk) begin
        if (rst | reset) read_index <= 0;
        else if (pop & ~empty) read_index <= read_index + 1;
    end
    always_ff @(posedge clk) begin
        if (rst | reset) write_index <= 0;
        else if (push & ~full) write_index <= write_index + 1;
    end

    // Output
    assign pop_data = ram[read_index];

    // Controll signals
    assign full = read_index == PTR_WIDTH'(write_index + 1);
    assign empty = read_index == write_index;

endmodule
