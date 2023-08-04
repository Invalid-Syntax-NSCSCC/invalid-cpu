BUILD_DIR = ./build

export PATH := $(abspath ./utils):$(PATH)

all: clean verilog

test:
	./millw -i __.test

verilog:
	mkdir -p $(BUILD_DIR)/final
	./millw -i __.test.runMain Elaborate -td $(BUILD_DIR)
	cp -f $(BUILD_DIR)/*CoreCpuTop.* $(BUILD_DIR)/final/
	cp -f ./verilog/* $(BUILD_DIR)/final/

chiplab:
	rm -rf $${CHIPLAB_HOME}/IP/myCPU/*
	cp $(BUILD_DIR)/final/* $${CHIPLAB_HOME}/IP/myCPU

help:
	./millw -i __.test.runMain Elaborate --help

compile:
	./millw -i __.compile

ide:
	./millw -i mill.bsp.BSP/install
	./millw -i --import ivy:com.lihaoyi::mill-contrib-bloop:  mill.contrib.bloop.Bloop/install
	./millw -i mill.scalalib.GenIdea/idea

reformat:
	./millw -i __.reformat

checkformat:
	./millw -i __.checkFormat

clean:
	rm -rf $(BUILD_DIR)

.PHONY: test verilog chiplab help compile ide reformat checkformat clean
