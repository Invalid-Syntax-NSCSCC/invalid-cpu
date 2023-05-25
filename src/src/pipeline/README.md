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

CSR地址转换在译码时实现

# 数据传递

使用`ready-valid`进行数据传递

# Pipeline Control Signal

## `flush`

Reset or make invalid every regs in current stage.
