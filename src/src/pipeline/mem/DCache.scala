package pipeline.mem

import chisel3._
import chisel3.util._
import pipeline.mem.bundles.{DCacheAccessPort, StatusTagBundle}
import pipeline.mem.enums.{DCacheState => State, ReadWriteSel}
import spec._

import scala.collection.immutable

class DCache extends Module {
  val io = IO(new Bundle {
    val accessPort = new DCacheAccessPort
  })

  // Read cache hit diagram:
  // clock: ^_____________________________^___________________
  //        isReady = T                   isReady = T
  //        isValid = T                   read.isValid = T
  //        rw = Read                     read.data = *data*
  //        (stored as previous request)
  //
  // state: Ready                         Ready

  // Read cache miss diagram:
  // clock: ^_____________________________^_________________________________^...^___________________^_____________________________________________________^...^______________
  //        isReady = T                   isReady = F                           (get axi data)      read.data = *data*
  //        isValid = T                                                         (fill cache)        read.isValid = T
  //        rw = Read
  //        (stored as previous request)  (axi read request from previous)
  //                                      [if swap out dirty]:                                      [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        (axi write request)                                       isReady = F                                               isReady = T
  //                                                                                                [else]:
  //                                                                                                  isReady = T
  //
  // state: Ready                         [if swap out dirty]:                                      [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        FetchForReadAndWb                                         OnlyWb                                                    Ready
  //                                      [else]:                                                   [else]:
  //                                        FetchForRead                                              Ready
  // note:
  //   - Set reg `isRequestSent` to F before entering state for cache miss,
  //     then set reg `isRequestSent` to T after entering

  // Write cache hit diagram:
  // clock: ^_____________________________^_______________^____________
  //        isReady = T                   isReady = F     isReady = T
  //        isValid = T                   isComplete = T
  //        rw = Write                    (write cache)
  //        (stored as previous request)
  //
  // state: Ready                         Write           Ready

  // Write cache miss diagram:
  // clock: ^_____________________________^_________________________________^...^________________________^_____________________________________________________^...^______________
  //        isReady = T                   isReady = F                           (get axi data)
  //        isValid = T                                                         (fill cache with write)
  //        rw = Write
  //        (stored as previous request)  (axi read request from previous)      write.isComplete = T
  //                                      [if swap out dirty]:                  isReady = F              [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        (axi write request)                                            isReady = F                                               isReady = T
  //                                                                                                     [else]:
  //                                                                                                       isReady = T
  //
  // state: Ready                         [if swap out dirty]:                                           [if previous swap out dirty and write not complete]:      [if previous swap out dirty and write complete]:
  //                                        FetchForWriteAndWb                                             OnlyWb                                                    Ready
  //                                      [else]:                                                        [else]:
  //                                        FetchForWrite                                                  Ready
  // note:
  //   - Set reg `isRequestSent` to F before entering state for cache miss,
  //     then set reg `isRequestSent` to T after entering

  // Status-tag line:
  // 1.W      1.W      TagW
  // isValid  isDirty  Tag

  // Data line:
  // MemDataW ... MemDataW
  // data     ... data

  // Mem addr:
  // TagW  IndexW      ByteOffsetW
  // tag   queryIndex  byteOffset
  //
  // note: Lowest 2 [log2(wordLength / byteLength)] bits of byteOffset should be 0

  // Note: Number of RAMs is the same as the size of a set

  val statusTagWidth = (new StatusTagBundle).getWidth

  // RAMs for valid, dirty, and tag
  val statusTagRams = Vec(
    Param.Count.DCache.setSize,
    Module(
      new SimpleRam(
        Param.Count.DCache.sizePerRam,
        statusTagWidth
      )
    )
  )

  // RAMs for data line
  val dataLineRams = Vec(
    Param.Count.DCache.setSize,
    Module(
      new SimpleRam(
        Param.Count.DCache.sizePerRam,
        Param.Width.DCache._dataLine
      )
    )
  )

  (statusTagRams ++ dataLineRams).foreach { ram =>
    ram.io              := DontCare
    ram.io.writePort.en := false.B // Fallback: Not write
  }

  val stateReg  = RegInit(State.Ready)
  val nextState = WireDefault(stateReg)
  stateReg := nextState // Fallback: Keep state

  val isReadValidReg = RegNext(false.B, false.B) // Fallback: Not valid
  io.accessPort.read.isValid := isReadValidReg

  val readDataReg = RegInit(0.U(Width.Mem.data))
  readDataReg             := readDataReg // Fallback: Keep data
  io.accessPort.read.data := readDataReg

  io.accessPort.isReady := false.B // Fallback: Not ready

  switch(stateReg) {
    is(State.Ready) {
      io.accessPort.isReady := true.B

      when(io.accessPort.isValid) {
        // Stage 1: Cache query

        // Decode
        val memAddr = WireDefault(io.accessPort.addr)
        val tag     = WireDefault(memAddr(Width.Mem._addr - 1, Width.Mem._addr - Param.Width.DCache.tag))
        val queryIndex = WireDefault(
          memAddr(Param.Width.DCache._byteOffset + Param.Width.DCache._addr - 1, Param.Width.DCache._byteOffset)
        )
        val byteOffset = WireDefault(memAddr(Param.Width.DCache._byteOffset - 1, 0))
        val dataIndex  = WireDefault(byteOffset(Param.Width.DCache._byteOffset - 1, log2Ceil(wordLength / byteLength)))

        // Read status-tag
        statusTagRams.foreach { ram =>
          ram.io.readPort.addr := queryIndex
        }
        val statusTagLines = Wire(Vec(Param.Count.DCache.setSize, new StatusTagBundle))
        statusTagLines.zip(statusTagRams.map(_.io.readPort.data)).foreach {
          case (line, data) =>
            line.isValid := data(statusTagWidth - 1)
            line.isDirty := data(statusTagWidth - 2)
            line.tag     := data(statusTagWidth - 3, 0)
        }

        // Read data (for read)
        dataLineRams.foreach { ram =>
          ram.io.readPort.addr := queryIndex
        }
        val dataLines = WireDefault(
          VecInit(
            Seq.fill(Param.Count.DCache.setSize)(0.U(Param.Width.DCache.dataLine))
          )
        )
        when(io.accessPort.rw === ReadWriteSel.read) {
          dataLines.zip(dataLineRams).foreach {
            case (line, ram) =>
              line := ram.io.readPort.data
          }
        }

        // Calculate if hit and select
        val isSelectedVec = WireDefault(VecInit(statusTagLines.map(line => line.isValid && (line.tag === tag))))
        val setIndex = WireDefault(
          MuxCase(
            0.U,
            isSelectedVec.zipWithIndex.map {
              case (isSelected, index) =>
                isSelected -> index.U
            }
          )
        )
        val selectedStatusTagLine = WireDefault(statusTagLines(setIndex))
        val selectedDataLine = WireDefault(
          VecInit(
            dataLines(setIndex).asBools
              .grouped(Width.Mem._data)
              .toSeq
              .map(VecInit(_).asUInt)
          )
        )
        val isCacheHit = WireDefault(isSelectedVec.reduce(_ || _))

        // Select data by data index from byte offset
        val selectedData = WireDefault(selectedDataLine(dataIndex))

        when(isCacheHit) {
          switch(io.accessPort.rw) {
            is(ReadWriteSel.read) {
              nextState := State.Ready

              // Remember to use regs
              isReadValidReg := true.B
              readDataReg    := selectedData

              // TODO: Last edited
            }
            is(ReadWriteSel.write) {}
          }
        }.otherwise {}
      }
    }
  }
}
