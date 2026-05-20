package i2c

import spinal.core._

/** Verilog generation entry point for [[I2cControllerDemo]].
  *
  * Run with `make gen-demo` (or `make all`, since `TOP := I2cControllerDemo`
  * in the Makefile). Mirrors `I2cControllerVerilog` — same
  * `targetDirectory = "gen"`, same one-line shape — so the toolchain
  * (yosys → nextpnr-ice40 → icepack) Just Works against the produced
  * `gen/I2cControllerDemo.v`.
  *
  * The demo composes `I2cController` and `uart.UartController` (pulled
  * in via the cross-project sbt dep declared in `I2c/build.sbt`).
  */
object I2cControllerDemoVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(targetDirectory = "gen").generateVerilog(I2cControllerDemo())
  }
}
