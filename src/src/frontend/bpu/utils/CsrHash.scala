package frontend.bpu.utils
import chisel3._
import chisel3.util._
import spec.Param

// Implement GHR hash using a CSR (Circular Shifted Register)
class CsrHash(
  inputLength:  Int = 25,
  outputLength: Int = 10)
    extends Module {
  val io = IO(new Bundle {
    val dataUpdate    = Input(Bool())
    val data          = Input(UInt(inputLength.W))
    val hash          = Output(UInt(outputLength.W))
    val isFixHash     = Input(Bool())
    val isRecoverHash = Input(Bool())
    val originHash    = Input(UInt(outputLength.W))
  })

  val csr     = RegInit(0.U(outputLength.W))
  val nextCSR = WireDefault(0.U(outputLength.W))

  val residual = (inputLength - 1) % outputLength
//  nextCSR           := Cat(csr(outputLength - 2, 0), csr(outputLength - 1) ^ io.data(0))
//  nextCSR(residual) := nextCSR(residual, residual) ^ io.data(inputLength - 1)

  if (Param.isSpeculativeGlobalHistory) {
    nextCSR := Mux(
      io.isRecoverHash,
      io.originHash,
      Mux(
        io.isFixHash,
        Cat(io.originHash(outputLength - 2, 0), io.originHash(outputLength - 1) ^ io.data(0)) ^ (io.data(
          inputLength - 1
        ) << residual).asUInt,
        Mux(
          io.dataUpdate,
          Cat(csr(outputLength - 2, 0), csr(outputLength - 1) ^ io.data(0)) ^ (io.data(
            inputLength - 1
          ) << residual).asUInt,
          csr
        )
      )
    )
  } else {
    // commit update
    nextCSR := Mux(
      io.dataUpdate,
      Cat(csr(outputLength - 2, 0), csr(outputLength - 1) ^ io.data(0)) ^ (io.data(
        inputLength - 1
      ) << residual).asUInt,
      csr
    )
  }

//  when(io.dataUpdate) {
//    csr := nextCSR
//  }
  csr := nextCSR

  io.hash := csr

}
