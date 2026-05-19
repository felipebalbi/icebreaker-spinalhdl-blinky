package i2c

import spinal.core._
import spinal.lib.bus.regif._

/** Documentation generator entry point for [[I2cController]].
  *
  * Runs the elaboration and then asks regif to dump the register file
  * documentation alongside the Verilog. Outputs in `gen/`:
  *
  *   - `i2c_controller.html` — datasheet table
  *   - `i2c_controller.h` — C header (offsets / shifts / masks)
  *   - `i2c_controller.json` — machine-readable register map
  *   - `i2c_controller.ralf` — UVM RALF for verification
  *   - `i2c_controller.rdl` — SystemRDL
  *
  * Run with `make docs`. Mirrors `UartControllerDocs` — same lineup, same
  * "elaborate then `.accept(...)` per format" idiom.
  */
object I2cControllerDocs {
  def main(args: Array[String]): Unit = {
    val report = SpinalConfig(targetDirectory = "gen")
      .generateVerilog(I2cController())
    report.toplevel.busif.accept(DocHtml("i2c_controller"))
    report.toplevel.busif.accept(DocCHeader("i2c_controller", "I2C"))
    report.toplevel.busif.accept(DocJson("i2c_controller"))
    report.toplevel.busif.accept(DocRalf("i2c_controller"))
    report.toplevel.busif.accept(DocSystemRdl("i2c_controller"))
  }
}
