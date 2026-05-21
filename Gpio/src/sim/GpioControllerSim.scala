package gpio

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver

/** APB-driven smoke test for [[GpioController]].
  *
  * Black-box sim mirroring `UartControllerSim` / `I2cControllerSim`: every
  * interaction goes through the APB port. Today the controller is a stub
  * (every output tied off; see `TODO.md` Step 3), so the only thing this
  * sim usefully proves is that the module elaborates, the APB handshake
  * completes, and reads return zeros — i.e. that the Makefile and Spinal
  * pipeline are wired up. As Steps 3–6 land, extend the body with the
  * cases listed in `TODO.md` Step 6:
  *
  *   1. caseRevisionReadback     — REVISION decode locks the
  *      Makefile → sys.props → regif path.
  *   2. caseDirOutLoopback       — DIR / OUT drive `gpio.write[E]`; with a
  *      testbench-side pad model that loops `write` back into `read`,
  *      `IN` mirrors `OUT`.
  *   3. caseInputSync2Cycle      — assert the 2-FF synchroniser delay.
  *   4. caseIsrW1C               — falling-edge → ISR set → IRQ asserted →
  *      W1C clears → IRQ deasserts.
  *   5. caseReservedBits         — with `numPins < 32`, writes to bits
  *      `[31:numPins]` are dropped on read.
  *   6. casePadConfigPassthrough — OPEN_DRAIN / PULL / PULL_TYPE /
  *      INPUT_ENABLE follow their registers bit-for-bit.
  *
  * The current body is the "scaffold pass": the sim runs to completion
  * with the stub controller so `make sim-controller` is green from day
  * one. Real coverage lands alongside the real controller body.
  *
  * Run: `sbt "runMain gpio.GpioControllerSim"`
  */
object GpioControllerSim {

  // Register offsets — must match GpioController.scala once Step 3 lands.
  private val REVISION     = 0x00
  private val CTRL         = 0x04
  private val STATUS       = 0x08
  private val ISR          = 0x0c
  private val IER          = 0x10
  private val DIR          = 0x14
  private val IN           = 0x18
  private val OUT          = 0x1c
  private val OPEN_DRAIN   = 0x20
  private val PULL         = 0x24
  private val PULL_TYPE    = 0x28
  private val INPUT_ENABLE = 0x2c
  private val EDGE_RISE    = 0x30
  private val EDGE_FALL    = 0x34
  private val CFG_INFO     = 0x38

  def main(args: Array[String]): Unit = {
    SimConfig.withWave
      .compile(GpioController(GpioConfig(numPins = 4)))
      .doSim("scaffold-smoke") { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        val apb = Apb3Driver(dut.io.apb, dut.clockDomain)

        // Drive the read-back side of the pad bus so it doesn't float.
        dut.io.gpio.read #= 0

        dut.clockDomain.waitSampling(5)

        // Stub controller returns zero on every read; that's the only
        // assertion we can make today. Once Step 3 is wired up, replace
        // this with the case matrix in the doc-comment above.
        val rev = apb.read(REVISION)
        println(f"REVISION = 0x${rev.toLong}%08x (stub returns 0)")

        // Smoke-write something to OUT; on the stub it has no effect.
        apb.write(OUT, 0x5)

        dut.clockDomain.waitSampling(10)

        println("OK: GpioControllerSim scaffold ran (stub controller; " +
          "extend body once Steps 3–6 land)")
      }
  }
}
