BUILD_DIR = ./build

export PATH := $(abspath ./utils):$(PATH)

test:
	./millw -i __.test

verilog:
	mkdir -p $(BUILD_DIR)
	./millw -i __.test.runMain Elaborate -td $(BUILD_DIR)
	# sh scripts/modify_verilog.sh $(BUILD_DIR)
	mkdir -p $(BUILD_DIR)/final
	# head -n -2 $(BUILD_DIR)/CoreCpuTop.v > $(BUILD_DIR)/final/CoreCpuTop.v
	awk -v n=3 'NR==FNR{total=NR;next} FNR==total-n+1{exit} 1' $(BUILD_DIR)/CoreCpuTop.v $(BUILD_DIR)/CoreCpuTop.v > $(BUILD_DIR)/final/CoreCpuTop.v
	# cp -f $(BUILD_DIR)/CoreCpuTop.v $(BUILD_DIR)/final
	cp -f ./verilog/cpu_top.v $(BUILD_DIR)/final

help:
	./millw -i __.test.runMain Elaborate --help

compile:
	./millw -i __.compile

bsp:
	./millw -i mill.bsp.BSP/install

reformat:
	./millw -i __.reformat

checkformat:
	./millw -i __.checkFormat

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: test verilog help compile bsp reformat checkformat clean
