#!/usr/bin/env sh

sed -E 's/CpuTop/core_top/ ;
        s/input[[:space:]]+clock/input aclk/ ;
        s/input[[:space:]]+reset/input aresetn/ ;
        s/\x29;/&\nwire clock;\nassign clock = aclk;\nreg reset;\nalways @(posedge aclk) reset <= ~aresetn;\n/' $1/CpuTop.v > $1/CpuTop_edited.v