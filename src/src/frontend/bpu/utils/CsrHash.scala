package frontend.bpu.utils
import chisel3._
import chisel3.util._

// Implement GHR hash using a CSR (Circular Shifted Register)
class CsrHash(
  inputLength:  Int = 25,
  outputLength: Int = 10)
    extends Module {
  val io = IO(new Bundle {
    val dataUpdate = Input(Bool())
    val data       = Input(UInt(inputLength.W))
    val hash       = Output(UInt(outputLength.W))
  })
  val CSR     = RegInit(0.U(outputLength.W))
  val nextCSR = WireDefault(0.U(outputLength.W))

  val residual = (inputLength - 1) % outputLength
  nextCSR           := Cat(CSR(outputLength - 1, 0), CSR(outputLength - 1) ^ io.data(0))
  nextCSR(residual) := nextCSR(residual) ^ io.data(inputLength - 1)

  when(io.dataUpdate) {
    CSR := nextCSR
  }

  io.hash := nextCSR

}
