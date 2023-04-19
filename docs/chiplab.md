# Chiplab使用指南

## 一、环境配置

可同步参考[官网手册](https://chiplab.readthedocs.io/zh/latest/Quick-Start.html)，但是官网手册有坑。

### 1. 下载Chiplab

首先从[官网](https://gitee.com/loongson-edu/chiplab/)处下载（克隆）Chiplab：

```shell
cd ~/Desktop
git clone https://gitee.com/loongson-edu/chiplab.git
```

### 2. 下载Chiplab的toolchains

本节内容与Chiplab中`toolchains/README.md`路径下的说明相同。

需要下载三样东西：

- [GCC交叉编译器](https://gitee.com/loongson-edu/la32r-toolchains/releases)
  
  根据自己电脑的架构，下载`loongarch32r-linux-gnusf-{日期}-{架构}.tar.gz`并解压，将`loongarch32r-linux-gnusf-{日期}`文件夹解压到Chiplab文件夹下的`toolchains`目录。

- [NEMU](https://gitee.com/wwt_panache/la32r-nemu/releases)

  在`toolchains`目录下新开一个`nemu`目录，把下载的`la32r-nemu-interpreter-so`放到`nemu`目录里面。

- [newlib](https://gitee.com/chenzes/la32r-newlib/releases/tag/newlib)

  下载`system_newlib.tar`，里面的文件夹解压到`toolchains`目录下。

最终，`toolchains`目录应该看起来像是这样：

```plain
toolchains
├── loongarch32r-linux-gnusf-2022-05-20
│   ├── bin...
│   ├── include...
│   ├── lib...
│   ├── lib64...
│   ├── libexec...
│   ├── loongarch32r-linux-gnusf...
│   ├── share...
│   └── sysroot...
├── nemu
│   └── la32r-nemu-interpreter-so
├── README.md
└── system_newlib
    ├── libc.a
    ├── libg.a
    ├── libm.a
    ├── libpmon.a
    ├── loongarch32-unknown-elf...
    ├── pmon.ld
    └── start.o
```

### 3. 配置环境变量

需要配置`CHIPLAB_HOME`环境变量，并将交叉编译工具链的二进制路径加入`PATH`变量中。

我的方法是在Chiplab根目录下创建一个脚本文件`setenv.sh`（然后需要使用Chiplab的时候`source setenv.sh`）。也可以把`source`的过程放到`.xxshrc`中，实现自动配置环境变量。

脚本文件内容是：

```shell
export CHIPLAB_HOME=$(cd "$(dirname "$0")"; pwd)
export PATH=$(echo $CHIPLAB_HOME/toolchains/loongarch32r-linux-gnusf-*/bin/):$PATH 
```

写完这个脚本文件，丢到Chiplab根目录即可。

### 4. 安装其他依赖

仿真需要用到verilator，官方建议版本大于`v4.224`，因此在Ubuntu上通过apt直接安装二进制是不现实的（版本太低），最好通过源码编译安装。

可参考[官方教程](https://verilator.org/guide/latest/install.html)安装：

```shell
# 编译和运行依赖
sudo apt install git perl python3 make autoconf g++ flex bison ccache ibgoogle-perftools-dev numactl perl-doc
sudo apt install libfl2 # 仅限Ubuntu（有错误请忽略）
sudo apt install libfl-dev # 仅限Ubuntu（有错误请忽略）
sudo apt install zlibc zlib1g zlib1g-dev # 仅限Ubuntu（有错误请忽略）

# 下载源码
cd ~/Desktop
git clone https://github.com/verilator/verilator

# 编译
unset VERILATOR_ROOT
cd verilator
git checkout v4.224
autoconf
./configure
make -j `nproc` # 多线程编译。如果报错就直接make吧

# 安装
sudo make install
```

## 二、使用Verilator/DIFFTEST

## Step 0: 拷贝 CPU Verilog 源码

到路径`$CHIPLAB_HOME/IP/myCPU`下。

## Step 1: 载入环境变量

```shell
source setenv.sh
```

## Step 2: 更改SoC配置

在`$CHIPLAB_HOME/chip/config-generator.mak`中可以配置SoC的部分参数，如AXI数据位宽、双发射等，详见[官方文档](https://chiplab.readthedocs.io/zh/latest/Simulation/verilator.html#soc)。

## Step 3: 编译仿真环境和测试程序

有两种仿真环境：程序仿真`run_prog`和随机验证`run_random`。

程序仿真的测试程序在`$CHIPLAB_HOME/software/`下。

```shell
cd $CHIPLAB_HOME/sims/verilator/run_prog # 随机验证的目录是run_random
./configure.sh --run func/func_lab3 # 选择运行的程序
make # 编译
```

通过`configure.sh`还可设置其他编译参数，可以参考其`--help`的输出或者[官方文档](https://chiplab.readthedocs.io/zh/latest/Simulation/verilator.html#id1)。

## Step 4: 配置运行参数

程序仿真的运行参数位于`$CHIPLAB_HOME/sims/verilator/run_prog/Makefile_run`中，若要输出波形，需要修改其中的`DUMP_WAVEFORM=1`。

运行参数的设置内容可以参考[官方文档](https://chiplab.readthedocs.io/zh/latest/Simulation/verilator.html#id2)。

## Step 5: 开始仿真

配置完运行参数后即可开始仿真。

```shell
make run # 开始仿真
gtkwave $CHIPLAB_HOME/sims/verilator/run_prog/log/func/func_lab*_log/simu_trace.fst
```
