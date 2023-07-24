# Invalid CPU

## 概述

Invalid Syntax 参赛队为 NSCSCC 2023 团队赛 LoongArch 赛道设计的 CPU 源代码。

## 使用方式

### 前置条件

请确保在开发系统上配置好 [chiplab](https://gitee.com/loongson-edu/chiplab/tree/chiplab_diff)、IntelliJ IDEA、JDK 等开发工具，并在 IDEA 中安装好 Scala 插件。

初次使用 IDEA 打开此项目的时候，需要执行命令 `make ide` 并在 IDEA 中重新加载 BSP 项目。

*待补充*

## 自动化任务

- `make test`：运行所有测试
- `make verilog`：构建并生成 Verilog 模块
- `scripts/refresh_branch.sh <BRANCH_NAME>`：将 `<BRANCH_NAME>` 分支强制同步到远端仓库的 main 分支。建议在每合并一个 PR 之后立即这样做，否则在下次利用此分支提交 PR 时会由于 squash merge 的原因导致引入无关 commits（参见 <https://stackoverflow.com/questions/16306012/github-pull-request-showing-commits-that-are-already-in-target-branch>）

其他自动化任务请参考 `Makefile` 和 <https://github.com/OpenXiangShan/chisel-playground>
