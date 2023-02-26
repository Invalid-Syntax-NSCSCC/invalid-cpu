# Invalid CPU

## 概述

Invalid Syntax 参赛队为 LoongArch 挑战赛 2023 设计的 CPU 源代码。

## 使用方式

### 前置条件

请确保在开发系统上配置好 [chiplab](https://gitee.com/loongson-edu/chiplab/tree/chiplab_diff)、IntelliJ IDEA、JDK 等开发工具，并在 IDEA 中安装好 Scala 插件。

*待补充*

## 自动化任务

- `make test`：运行所有测试
- `make verilog`：构建并生成 Verilog 模块

其他自动化任务请参考 `Makefile` 和 <https://github.com/OpenXiangShan/chisel-playground>