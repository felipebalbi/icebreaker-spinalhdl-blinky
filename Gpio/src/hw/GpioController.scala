package gpio

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.io.TriStateArray

/** Parameterizable, register-mapped GPIO controller.
  *
  * **Scaffold-only.** The `Component` body below ties every output off so
  * elaboration produces a syntactically-valid module that the toolchain
  * can synthesise and pack into a bitstream. The real register file
  * (REVISION / CTRL / STATUS / DIR / IN / OUT / PULL / PULL_TYPE / OD / IE /
  * ISR / IER / CFG_INFO), the per-pin edge-detect / interrupt logic, and the
  * 2-FF input synchronisers all land in subsequent steps — see the planned
  * address map and step breakdown in `Gpio/TODO.md`.
  *
  * The IO surface is the **stable contract** for downstream consumers:
  *
  *   - `apb`        : APB3 slave fronting the register file. 8-bit byte
  *     address (256-byte window), 32-bit data, no PSLVERR.
  *   - `gpio`       : per-pin tri-state pad bus. `write` / `writeEnable`
  *     drive the pad; `read` is the post-synchroniser sample of the pad.
  *     The `writeEnable` bit per pin tracks the `DIR` register (1 = output).
  *   - `openDrain`  : 1 per pin → pad is open-drain (drives low only;
  *     `write=1` releases). 0 → push-pull. Mapped onto `SB_IO` `PIN_TYPE` at
  *     the demo top in Step 9.
  *   - `pullEnable` : 1 per pin → enable the pad's internal pull. iCE40 only
  *     has one polarity per side of the package, so `pullType` selects which.
  *   - `pullType`   : 0 = pull-down, 1 = pull-up. Latched alongside
  *     `pullEnable` and consumed by the pad-mapping layer.
  *   - `inputEnable`: 1 per pin → enable the pad's input buffer. Disable for
  *     unused pins to save leakage and reduce noise on shared dies.
  *   - `irq`        : OR-reduction of `(ISR & IER)` gated by the master
  *     enable, mirroring the `UartController` / `I2cController` convention.
  *
  * @param cfg
  *   Compile-time configuration — see [[GpioConfig]]. The only knob is the
  *   number of physical pins; the register-file width and the APB address
  *   space are fixed.
  */
case class GpioController(cfg: GpioConfig = GpioConfig()) extends Component {

  val apb3Config = Apb3Config(
    addressWidth = cfg.apbAddrWidth,
    dataWidth = 32,
    selWidth = 1,
    useSlaveError = false
  )

  val io = new Bundle {
    val apb         = slave(Apb3(apb3Config))
    val gpio        = master(TriStateArray(cfg.numPins bits))
    val openDrain   = out Bits (cfg.numPins bits)
    val pullEnable  = out Bits (cfg.numPins bits)
    val pullType    = out Bits (cfg.numPins bits)
    val inputEnable = out Bits (cfg.numPins bits)
    val irq         = out Bool ()
  }

  // --------------------------------------------------------------
  // SCAFFOLD STUB.
  //
  // Every output is tied off so the module elaborates and yosys can
  // synthesise it. The real implementation (regif, edge-detect, sync)
  // lands in TODO.md Steps 3–6. Until then `make` succeeds but the
  // controller is functionally inert: APB reads return 0, APB writes
  // are silently discarded, and the pad bus stays high-Z.
  // --------------------------------------------------------------

  io.apb.PREADY := True
  io.apb.PRDATA := B(0, 32 bits)

  io.gpio.write       := B(0, cfg.numPins bits)
  io.gpio.writeEnable := B(0, cfg.numPins bits)

  io.openDrain   := B(0, cfg.numPins bits)
  io.pullEnable  := B(0, cfg.numPins bits)
  io.pullType    := B(0, cfg.numPins bits)
  io.inputEnable := B(0, cfg.numPins bits)

  io.irq := False
}
