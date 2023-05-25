# 发射设计
双发射：顺序发射+（伪）乱序执行+顺序写回 (未实现寄存器重命名，无法解决RAW造成的阻塞浪费)

# 线路复用情况

1. decode info for exeState
- 分支跳转地址 jumpBranchAddr
- 访存立即数 loadStoreImm
- csr地址 csrAddr
- syscall和break的code

2. csr read and write
- 带掩码的csr写操作，掩码放置到gpyWritePort中的data域


# 译码情况

break和syscall译码过程只检查是否match和确定ExeOp，code等到Cu再查看（***UNFINISH***）

CSR地址转换在译码时实现(***UNFINISH***)

# Pipeline Control Signal

## `flush`

Reset or make invalid every regs in current stage.

## `stall`

When a stage issue a stall request, `Control Unit` stall every previous stage but not the requester.

Stall Behaviours:

Stage | Behaviour
---|---
IssueStage | no issue
RegReadStage | no output
ExeStage | no output, but continue mul or div if started


## `clear`

If stall behaviour is **as mention above**, 
it is not neccessary so far as `IssueStage`, `RegReadStage` and `ExeStage` were concerned. 

However, `RenameStage` and `RobStage` may require the previous stage to have a `clear` singal to avoid repeatly rename or commit. So keep it temporarily.