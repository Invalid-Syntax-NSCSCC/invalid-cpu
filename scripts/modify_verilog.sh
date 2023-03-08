#!/usr/bin/env sh

env LC_ALL=C LANG=C perl -0777 -pe 's/CpuTop/core_top/ ;
                                    s/input[[:space:]]+clock/input aclk/ ;
                                    s/input[[:space:]]+reset/input aresetn/ ;
                                    s/\)\;\n/\)\;\nwire clock;\nassign clock = aclk;\nreg reset;\nalways @(posedge aclk) reset <= ~aresetn;\n/' $1/CpuTop.v > $1/CpuTop_edited.v