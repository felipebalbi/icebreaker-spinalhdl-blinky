package i2c

import spinal.core._

/** Verilog generation entry point for [[I2cController]] in isolation.
  *
  * Run with `make gen-controller`. Mirrors `UartControllerVerilog` — same
  * `targetDirectory = "gen"`, same one-line shape — so the Makefile's
  * `gen-controller` target (already declared in `I2c/Makefile`) Just Works.
  */
object I2cControllerVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(targetDirectory = "gen").generateVerilog(I2cController())
  }
}
