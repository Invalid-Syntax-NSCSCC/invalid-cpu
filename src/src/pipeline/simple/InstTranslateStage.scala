package pipeline.simple

import chisel3._
import spec._
import chisel3.util.Decoupled
import chisel3.experimental.Param
import pipeline.common.bundles.FetchInstInfoBundle
import common.bundles.RfAccessInfoNdPort
import memory.enums.TlbMemType
import spec.Inst._
import chisel3.util._
import spec.Width
import spec.ExeInst.OpBundle

class InstTranslateStage extends Module {

  val io = IO(new Bundle {
    val ins = Vec(
      Param.issueInstInfoMaxNum,
      Flipped(Decoupled(new FetchInstInfoBundle))
    )
    val outs = Vec(
      Param.issueInstInfoMaxNum,
      Decoupled(new FetchInstInfoBundle)
    )
    val isFlush = Input(Bool())

    val isInnerJump   = Input(Bool())
    val jumpDirection = Input(Bool())
    val jumpOffset    = Input(UInt(10.W))
  })

  io.ins.zip(io.outs).foreach {
    case (in, out) =>
      in <> out
  }
  if (Param.hasCustomInstruction) {

    val customContinueReg = RegInit(false.B)
    when(customContinueReg) {
      io.ins.foreach { in =>
        in.ready := false.B
      }
      io.outs.zipWithIndex.foreach {
        case (out, idx) =>
          if (idx != 0) {
            out.valid := false.B
          }
      }
    }

    val storeInfoReg = RegInit(FetchInstInfoBundle.default)

    val inst = storeInfoReg.inst
    val rd   = inst(4, 0)
    val rj   = inst(9, 5)
    val rk   = inst(14, 10)

    // write reg ; read 1, read 2 ;
    // imm ; has imm ; jump branch addr ; exe op ; issue main pipeline ;
    // jump offset ; jump direction (false is to next, true is to previous)

    // if use read zero, please use Reg[63] but NOT Reg[0] ; Reg[0] is use to no read
    var raw_seqs: Seq[Seq[Data]] =
      if (Param.testSub) {
        Seq(
          // Seq(33.U, 63.U, rk, 0.U, false.B, 0.U, OpBundle.nor, false.B, 1.U, false.B),
          // Seq(33.U, 33.U, 0.U, 1.U, true.B, 0.U, OpBundle.add, false.B, 1.U, false.B),
          // Seq(rd, rj, 33.U, 0.U, false.B, 0.U, OpBundle.add, false.B, 1.U, false.B)

          // add x33, rk, x63
          Seq(33.U, rk, 63.U, 0.U, false.B, 0.U, OpBundle.add, false.B, 1.U, false.B),
          // add x35, x63, x63    x35 = 0
          Seq(35.U, 63.U, 63.U, 0.U, false.B, 0.U, OpBundle.add, false.B, 1.U, false.B),
          // ori x34, x63, 4      x34 = 4
          Seq(34.U, 63.U, 0.U, 4.U, true.B, 0.U, OpBundle.or, false.B, 1.U, false.B),
          // addi x35, x35, 1     x35 += 1
          Seq(35.U, 35.U, 0.U, 1.U, true.B, 0.U, OpBundle.add, false.B, 1.U, false.B),
          // nor x33, x33, x63
          Seq(33.U, 33.U, 63.U, 0.U, false.B, 0.U, OpBundle.nor, false.B, 1.U, false.B),
          // cus_bne x35, x34 , -3  continue x35 + 1, x33 = ~x33 for 5 time
          Seq(0.U, 35.U, 34.U, 0.U, false.B, 0.U, OpBundle.custom_bne, false.B, 3.U, true.B),
          // addi x36, rj, 1
          Seq(36.U, rj, 0.U, 1.U, true.B, 0.U, OpBundle.add, false.B, 1.U, false.B),
          // nor x33, x33, x63
          Seq(33.U, 33.U, 63.U, 0.U, false.B, 0.U, OpBundle.nor, false.B, 1.U, false.B),
          // add rd, x33, x36
          Seq(rd, 33.U, 36.U, 0.U, false.B, 0.U, OpBundle.add, false.B, 1.U, false.B)
        )
      } else if (Param.testB) {

        val imm16 = inst(25, 10)
        val imm26 = Wire(SInt(32.W))
        imm26 := Cat(rj, rd, imm16, 0.U(2.W)).asSInt
        val jumpPc = imm26.asUInt + storeInfoReg.pcAddr
        Seq(
          Seq(33.U, 63.U, 0.U, 0.U, false.B, 0.U, OpBundle.rdcntvl_w, true.B, 1.U, false.B), // 33 : get a time val
          Seq(34.U, 63.U, 0.U, 1.U, true.B, 0.U, OpBundle.add, false.B, 1.U, false.B), // 34 : 1
          Seq(
            34.U,
            33.U,
            34.U,
            0.U,
            false.B,
            0.U,
            OpBundle.div,
            false.B,
            1.U,
            false.B
          ), // 34 : 33 div 1 , as the same as 33
          Seq(0.U, 34.U, 33.U, 0.U, false.B, jumpPc, OpBundle.beq, true.B, 0.U, false.B) // beq 33, 34 jump
        )
      } else if (Param.testSt_w) {
        val imm12 = inst(21, 10)
        val sext  = Wire(SInt(32.W))
        sext := imm12.asSInt
        Seq(
          Seq(33.U, rj, 0.U, sext.asUInt, true.B, 0.U, OpBundle.add, false.B, 1.U, false.B),
          Seq(0.U, 33.U, rd, 0.U, false.B, 0.U, OpBundle.st_w, true.B, 1.U, false.B)
        )
      } else {

        val imm5a = inst(24, 20)
        val imm5b = inst(19, 15)
        val imm5c = inst(14, 10)
        val rj    = inst(9, 5)
        val rd    = inst(4, 0)

        val mask = Wire(Vec(32, Bool()))
        mask.zipWithIndex.foreach {
          case (elem, idx) =>
            when(
              (imm5c >= idx.U && idx.U > imm5b) ||
                (idx.U > imm5b && imm5b > imm5c) ||
                (idx.U <= imm5c && imm5b > imm5c)
            ) {
              elem := true.B
            }.otherwise {
              elem := false.B
            }

        }

        // write reg ; read 1, read 2 ;
        // imm ; has imm ; jump branch addr ; exe op ; issue main pipeline ;
        // jump offset ; jump direction (false is to next, true is to previous)

        Seq(
          // shift
          Seq(33.U, rj, 0.U, imm5a, true.B, 0.U, OpBundle.sll, false.B, 1.U, false.B),
          Seq(34.U, 63.U, 0.U, 32.U, true.B, 0.U, OpBundle.add, false.B, 1.U, false.B),
          Seq(34.U, 34.U, 0.U, imm5a, true.B, 0.U, OpBundle.sub, false.B, 1.U, false.B),
          Seq(34.U, rj, 34.U, 0.U, false.B, 0.U, OpBundle.srl, false.B, 1.U, false.B),
          Seq(35.U, 34.U, 33.U, 0.U, false.B, 0.U, OpBundle.add, false.B, 1.U, false.B),

          // and
          Seq(rd, 35.U, 0.U, mask.asUInt, true.B, 0.U, OpBundle.and, false.B, 1.U, false.B)
        )
      }

    val seqs = Wire(
      Vec(
        raw_seqs.length,
        (new Bundle {
          val wreg                = UInt(Width.Reg.addr)
          val rreg1               = UInt(Width.Reg.addr)
          val rreg2               = UInt(Width.Reg.addr)
          val imm                 = UInt(wordLength.W)
          val hasImm              = Bool()
          val jumpBranchAddr      = UInt(wordLength.W)
          val exeOp               = new ExeInst.OpBundle
          val isIssueMainPipeline = Bool()
          val jumpDirection       = Bool()
          val jumpOffsetIndex     = UInt(10.W)
        })
      )
    )

    seqs.zip(raw_seqs).foreach {
      case (seq, raw_seq) =>
        seq.wreg                := raw_seq(0)
        seq.rreg1               := raw_seq(1)
        seq.rreg2               := raw_seq(2)
        seq.imm                 := raw_seq(3)
        seq.hasImm              := raw_seq(4)
        seq.jumpBranchAddr      := raw_seq(5)
        seq.exeOp               := raw_seq(6)
        seq.isIssueMainPipeline := raw_seq(7)
        seq.jumpOffsetIndex     := raw_seq(8)
        seq.jumpDirection       := raw_seq(9)
    }

    val counter = RegInit(zeroWord)

    val isCustomInsts = io.ins.map { in =>
      in.valid && (
        if (Param.testSub) in.bits.inst(31, 15) === _3R.sub_w
        else if (Param.testB) in.bits.inst(31, 26) === _2RI16.b_
        else if (Param.testSt_w) in.bits.inst(31, 22) === _2RI12.st_w
        else in.bits.inst(31, 25) === "b1100001".U
      )
    }
    isCustomInsts.zipWithIndex.foreach {
      case (isCustomInst, idx) =>
        if (idx != 0) {
          when(isCustomInst) {
            io.ins(idx).ready  := false.B
            io.outs(idx).valid := false.B
          }
        } else {
          // idx == 0
          when(isCustomInst && !customContinueReg) {
            storeInfoReg      := io.ins.head.bits
            customContinueReg := true.B
            counter           := seqs.length.U
            io.outs.foreach(_.valid := false.B)
            io.ins(1).ready := false.B
            io.ins(0).ready := true.B
          }
        }
    }

    val isJumpBlockReg = RegInit(false.B)
    when(isJumpBlockReg) {
      io.outs.head.valid := false.B
      when(io.isInnerJump) {
        isJumpBlockReg := false.B
        // counter        := seqs.length.U - io.jumpAbsIndex
        counter := Mux(
          io.jumpDirection,
          counter + io.jumpOffset,
          counter - io.jumpOffset
        )
      }
    }

    when(counter =/= 0.U) {
      val majorOut = io.outs.head
      when(majorOut.ready && !isJumpBlockReg) {
        majorOut.valid := true.B
        majorOut.bits  := storeInfoReg
        counter        := Mux(majorOut.bits.customInstInfo.isInnerJump, counter, counter - 1.U)
        when(majorOut.bits.customInstInfo.isInnerJump) {
          isJumpBlockReg := true.B
        }

        val res     = seqs(seqs.length.U - counter)
        val outInfo = majorOut.bits.customInstInfo
        majorOut.bits.customInstInfo.isCustom := true.B
        when(counter === 1.U) {
          majorOut.bits.customInstInfo.isCommit := true.B
          customContinueReg                     := false.B
        }

        def fillRfAccess(port: RfAccessInfoNdPort, addr: UInt): Unit = {
          port.en   := addr =/= 0.U
          port.addr := addr
        }

        fillRfAccess(outInfo.gprWrite, res.wreg)
        fillRfAccess(outInfo.gprReadPorts(0), res.rreg1)
        fillRfAccess(outInfo.gprReadPorts(1), res.rreg2)
        outInfo.imm                 := res.imm
        outInfo.hasImm              := res.hasImm
        outInfo.jumpBranchAddr      := res.jumpBranchAddr
        outInfo.op                  := res.exeOp
        outInfo.isIssueMainPipeline := res.isIssueMainPipeline
        outInfo.isInnerJump         := res.exeOp === OpBundle.custom_beq || res.exeOp === OpBundle.custom_bne
        outInfo.jumpDirection       := res.jumpDirection
        outInfo.jumpOffset          := res.jumpOffsetIndex
      }
    }

    when(io.isFlush) {
      customContinueReg := false.B
      isJumpBlockReg    := false.B
      counter           := 0.U
    }
  }
}
