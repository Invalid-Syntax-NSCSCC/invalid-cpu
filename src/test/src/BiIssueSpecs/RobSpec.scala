import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import pipeline.dispatch.bundles.InstInfoBundle
import utest._
import control.bundles.PipelineControlNdPort
import pipeline.queue.{BiInstQueue, InstQueue}
import spec.wordLength
import spec.zeroWord
import spec.PipelineStageIndex
import utils.MinFinder
import pipeline.rob.RobStage

object RobSpec extends ChiselUtestTester {
  val tests = Tests {
    test("Test RobStage") {
      testCircuit(new RobStage, Seq(WriteVcdAnnotation)) { 
        robModule => if (true) {
          def rob = robModule.io
          rob.readPorts.foreach{readPort => readPort.en poke false.B; readPort.addr poke zeroWord}
          rob.idDistributePorts.foreach(_.writeEn poke false.B)
          rob.writeReadyPorts.foreach{writePort => writePort.en poke false.B; writePort.addr poke zeroWord; writePort.data poke zeroWord}
          rob.instReadyIds.foreach(_ poke zeroWord)
        }
      }
    }
  }
}