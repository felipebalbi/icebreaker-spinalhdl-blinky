package gpio

import spinal.core._

/** Verilog generation entry point for [[GpioController]] in isolation.
  *
  * Run with `make gen-controller`. Mirrors `I2cControllerVerilog` /
  * `UartControllerVerilog` — same `targetDirectory = "gen"`, same one-line
  * shape — so the Makefile's `gen-controller` target Just Works.
  */
object GpioControllerVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(targetDirectory = "gen").generateVerilog(GpioController())
  }
}
