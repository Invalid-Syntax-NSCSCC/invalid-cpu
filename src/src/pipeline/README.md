# 线路复用情况

1. decode info for exeState
- 分支跳转地址 jumpBranchAddr
- 访存立即数 loadStoreImm
- csr地址 csrAddr

2. csr read and write
- 带掩码的csr写操作，掩码放置到gpyWritePort中的data域


# 译码情况
break和syscall译码过程只检查是否match和确定ExeOp，code等到Cu再查看