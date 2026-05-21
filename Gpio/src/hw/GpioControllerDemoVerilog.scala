package gpio

import spinal.core._

/** Verilog generation entry point for [[GpioControllerDemo]].
  *
  * Run with `make gen-demo` (or `make all`, since `TOP := GpioControllerDemo`
  * in the Makefile). Mirrors `I2cControllerDemoVerilog` /
  * `UartEchoDemoVerilog` — same `targetDirectory = "gen"`, same one-line
  * shape — so the toolchain (yosys → nextpnr-ice40 → icepack) Just Works
  * against the produced `gen/GpioControllerDemo.v`.
  */
object GpioControllerDemoVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(12 MHz),
      targetDirectory = "gen"
    ).generateVerilog(GpioControllerDemo())
  }
}
