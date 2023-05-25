import chisel3._
import chisel3.util._

package object spec {
  // Immutable definitions according to LA32R documentation go here

  val byteLength       = 8
  val wordLength       = 32
  val doubleWordLength = wordLength * 2
  val wordLog          = log2Ceil(wordLength)

  val zeroWord = 0.U(wordLength.W)

  object Pc {
    val init = "h1C000000".U(wordLength.W)
  }

  object RegIndex {
    val r0 = 0.U
    val r1 = 1.U
  }

  object PipelineStageIndex {
    var count = -1

    private def next = {
      count += 1
      count
    }

    val fronted        = next
    val issueStage     = next
    val regReadStage   = next
    val exeStage       = next
    val addrTransStage = next
    val memReqStage    = next
    val memResStage    = next
  }

  object ReadWriteMode {
    val read  = false.B
    val write = true.B
  }

  object Width {
    val inst = wordLength.W
    object Reg {
      val addr = wordLog.W
      val data = wordLength.W
    }

    object Csr {
      val addr           = 14.W
      val data           = wordLength.W
      val exceptionIndex = log2Ceil(spec.Csr.ExceptionIndex.count + 1).W
    }

    object Op {
      val _2RI12 = 10.W
      val _2RI14 = 8.W
      val _2RI16 = 6.W
      val _2R    = 22.W
      val _3R    = 17.W
      val _4R    = 12.W
    }

    object Mem {
      val _addr = wordLength
      val _data = wordLength

      val addr = _addr.W
      val data = _data.W
    }

    object Tlb {
      val _vppn = Mem._addr - 13
      val _ppn  = Mem._addr - 12

      val asid = 10.W
      val ps   = 6.W
      val vppn = _vppn.W
      val ppn  = _ppn.W
      val op   = 5.W
    }

    object Axi {
      val addr = wordLength.W
    }
  }

  object Count {
    val reg    = wordLength
    val csrReg = Csr.Index.count + 1
  }

  object Value {
    object Tlb {
      object Ps {
        val _4Kb = 12.U(Width.Tlb.ps)
        val _4Mb = 22.U(Width.Tlb.ps)
      }
    }

    object Axi {
      object Burst {
        val fixed    = 0.U(2.W)
        val incr     = 1.U(2.W)
        val wrap     = 2.U(2.W)
        val reserved = 3.U(2.W)
      }

      object Size {
        def get(sizeInBytes: Int) = log2Ceil(sizeInBytes).U(3.W)

        val _4_B   = get(4)
        val _128_B = get(128)
      }

      // AXI 3
      object Lock {
        val normal = 0.U(2.W)
      }

      object Cache {
        val nonBufferable = 0.U(4.W)
      }

      object Protect {
        def get(isPrivileged: Boolean, isSecure: Boolean, isInst: Boolean) =
          Cat(
            if (isInst) true.B else false.B,
            if (isSecure) false.B else true.B,
            if (isPrivileged) true.B else false.B
          )
      }

      object Response {
        val Okay          = 0.U(2.W)
        val ExclusiveOkay = 1.U(2.W)
        val SlaveErr      = 2.U(2.W)
        val DecodeErr     = 3.U(2.W)
      }
    }

    object Csr {
      object Crmd {
        object Plv {
          val high = 0.U(2.W)
          val low  = 3.U(2.W)
        }
        object Datm {
          val suc = 0.U(2.W)
          val cc  = 1.U(2.W)
        }
      }
    }
  }
}
