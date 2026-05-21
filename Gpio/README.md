# Gpio

A from-scratch parameterizable GPIO controller in SpinalHDL, targeting the
iCEbreaker. APB3-fronted, 32-bit register file, up to 32 pins per block.
Mirrors the `Uart` and `I2c` projects in shape — same REVISION /
CTRL / STATUS / ISR / IER skeleton at offset 0x00.

Status: **scaffold only.** The project tree, `Makefile`, `pcf`, top-level
`Component`s, and Verilog entry points exist. `make` runs end-to-end
(SpinalHDL → Verilog → yosys → nextpnr-ice40 → icepack) and produces a
flashable bitstream, but the controller's register file and the demo's
APB master FSM are stubbed out (see `TODO.md`). Both LEDs stay dark on
hardware until Steps 3–7 land. See `TODO.md` for the full bottom-up
bring-up plan.

## What's in scope

- **Parameterizable pin count.** Single elaboration knob, `numPins ∈ [1, 32]`.
  The user-visible register file is always 32 bits wide; bits beyond
  `numPins` are reserved-as-0 so software drivers see a stable contract
  across instance widths.
- **Per-pin programmable direction**, output value, input sample, open-drain
  mode, internal pull enable + polarity (up/down), input-buffer enable —
  all visible as one bit per pin in dedicated 32-bit registers.
- **Per-pin edge interrupt** (rising / falling / both, configurable),
  sticky-W1C `ISR` + `IER` mirroring the `UartController` /
  `I2cController` convention.
- **APB3 slave** at byte addresses 0x00..0xFF (256 byte window, plenty
  of headroom for future per-pin features).
- **iCE40 `SB_IO` mapping** of the per-pin sidebands (open-drain, pull,
  input enable) at the demo top — the controller stays
  technology-agnostic; the demo top picks the pad primitive.

## What's out of scope (for now)

- More than 32 pins per instance. Use multiple `GpioController`s at
  different APB base addresses instead.
- Per-pin alternate functions (muxing GPIO bits onto on-chip
  peripherals). Phase 3 stretch goal.
- Atomic SET / CLEAR / TOGGLE registers à la Cortex-M GPIO. Phase 3.
- Per-pin slew-rate / drive-strength selection. iCE40 doesn't expose
  those knobs anyway.

## Layout

```
Gpio/
  README.md          this file
  TODO.md            phased bring-up plan + design notes per step
  Makefile
  build.sbt
  icebreaker.pcf     clk + 2 LEDs + user button
  src/
    hw/              synthesizable code
    sim/             SpinalSim testbenches (empty until Step 6)
```

## Register layout (planned)

```
0x00 REVISION       RO   IP version (major.minor.patch), sourced from Makefile
0x04 CTRL           RW   master enable + irq enable
0x08 STATUS         RO   live status bits
0x0C ISR            W1C  per-pin edge events; write 1 to clear
0x10 IER            RW   per-pin interrupt enable; matches ISR
0x14 DIR            RW   per-pin direction (0 = input, 1 = output)
0x18 IN             RO   per-pin pad sample (post-synchroniser)
0x1C OUT            RW   per-pin output value (driven when DIR = 1)
0x20 OPEN_DRAIN     RW   per-pin open-drain mode (1 = drive low only)
0x24 PULL           RW   per-pin pull enable
0x28 PULL_TYPE      RW   per-pin pull polarity (0 = down, 1 = up)
0x2C INPUT_ENABLE   RW   per-pin input-buffer enable
0x30 EDGE_RISE      RW   per-pin "fire ISR on rising edge"
0x34 EDGE_FALL      RW   per-pin "fire ISR on falling edge"
0x38 CFG_INFO       RO   build-time parameters (numPins, ...)
```

`make docs` will dump an HTML datasheet, a C header, JSON, RALF and
SystemRDL into `gen/` once `GpioController` lands (see Step 6 in
`TODO.md`).

## Demo

`GpioControllerDemo` (the `TOP` of `make`) blinks the red on-board LED
at 1 Hz from the APB master, while polling the user button and toggling
the green LED on every (debounced) falling edge. End-to-end test of
the controller's read + write paths, and a smoke check that the
software contract (DIR / OUT / IN registers) actually moves pins.

Pin map (`icebreaker.pcf`):

| Signal           | iCE40 pin | Board net   |
| ---------------- | --------- | ----------- |
| `io_clk`         | 35        | 12 MHz osc  |
| `io_reset`       | 10        | uButton (USR / BTN_N), active-LOW |
| `io_led_blink`   | 11        | LEDR_N      |
| `io_led_edge`    | 37        | LEDG_N      |
| `io_btn`         | 18        | BTN1 on the snap-off section, active-LOW |

Reset is asynchronous, active-LOW from the uButton (pin 10), matching the
`Uart` and `I2c` projects' convention. The on-board uButton is therefore
*not* a GPIO; the GPIO demo input is BTN1 on the snap-off section. Use
BTN2 (pin 19) or BTN3 (pin 20) instead by editing `icebreaker.pcf`.

## Quickstart

```sh
cd Gpio
make           # bitstream
make sim       # run all sims (lands with Step 6)
make flash     # program the iCEbreaker
make help      # every target with its description
```

## Hardware notes

- The snap-off BTN1 is **active-LOW** with an external pull-up. The
  debouncer (Step 8) takes the synchronised pad sample and produces
  clean falling-edge pulses on press.
- The on-board LEDs are driven through SB_IO push-pull pads; writing
  1 lights the LED. Open-drain mode (Step 9 / register `OPEN_DRAIN`)
  is exercised on the PMOD pin set, not on the on-board LEDs.
- For pull-up / pull-down experimentation use a PMOD pin with a
  scope or logic analyser — the iCE40 internal pull is a weak
  ~10 kΩ, so the line takes microseconds to slew.
