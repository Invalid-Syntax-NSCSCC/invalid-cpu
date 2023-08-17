package pipeline.simple

import chisel3._
import spec._
import chisel3.util.Decoupled
import chisel3.experimental.Param
import pipeline.common.bundles.FetchInstInfoBundle
import common.bundles.RfAccessInfoNdPort
import memory.enums.TlbMemType
import spec.Inst._

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

    // write reg ; read 1, read 2 ; imm ; has imm ; jump branch addr ; exe op
    val raw_seqs = Seq(
      Seq(33.U, 34.U, inst(9, 5), 0.U, 0.U, 0.U, ExeInst.OpBundle.nor.asUInt),
      Seq(33.U, 33.U, 0.U, 1.U, 1.U, 0.U, ExeInst.OpBundle.add.asUInt),
      Seq(inst(4, 0), inst(14, 10), 33.U, 0.U, 0.U, 0.U, ExeInst.OpBundle.add.asUInt)
    )
    val seqs: Vec[Vec[UInt]] = Wire(Vec(raw_seqs.length, Vec(raw_seqs.head.length, UInt(32.W))))

    seqs.zip(raw_seqs).foreach {
      case (seq, raw_seq) =>
        seq.zip(raw_seq).foreach {
          case (dst, src) =>
            dst := src
        }
    }

    val counter = RegInit(zeroWord)

    val isCustomInsts = io.ins.map { in =>
      in.valid && in.bits.inst(31, 15) === _3R.sub_w
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

    when(counter =/= 0.U) {
      val majorOut = io.outs.head
      when(majorOut.ready) {
        majorOut.valid := true.B
        majorOut.bits  := storeInfoReg
        counter        := counter - 1.U

        val res: Vec[UInt] = seqs(seqs.length.U - counter)
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

        fillRfAccess(outInfo.gprWrite, res(0))
        fillRfAccess(outInfo.gprReadPorts(0), res(1))
        fillRfAccess(outInfo.gprReadPorts(1), res(2))
        outInfo.imm            := res(3)
        outInfo.hasImm         := res(4) =/= 0.U
        outInfo.jumpBranchAddr := res(5)
        outInfo.op             := res(6).asTypeOf(outInfo.op)
      }
    }

    when(io.isFlush) {
      customContinueReg := false.B
      counter           := 0.U
    }

  }
}
