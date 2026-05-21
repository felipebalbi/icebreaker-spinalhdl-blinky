# Gpio — TODO

Phased bring-up plan for a parameterizable, register-mapped GPIO
controller in SpinalHDL, targeting the iCEbreaker. Mirrors the
`Uart` and `I2c` projects in shape — same APB3-fronted regif at
offset 0x00, same `## ✅ Done` / `### ✅ Step N` / `### 🔲 Step N`
convention, same step-numbered-across-phases scheme.

The plan is intentionally unambitious. The whole point of this
project is to be the *easy win* between bigger blocks — no
state-machine head-banging, no metastability gauntlet, no
multi-master arbitration. The user-facing "wow" is small (an LED
blinks, another toggles on a button), but the IP underneath is
genuinely reusable as a CSR-attached GPIO bank in any future
SoC, and it exercises iCE40 `SB_IO` pad primitives that neither
`Uart` nor `I2c` touched.

## Project shape

```
Gpio/
  README.md          user-facing overview
  TODO.md            this file — bring-up plan
  AGENTS.md          per-project conventions
  Makefile           Spinal -> Verilog -> bitstream + sim targets
  build.sbt
  icebreaker.pcf     clk + reset + 2 LEDs + GPIO button
  src/
    hw/              synthesizable code
    sim/             SpinalSim testbenches (empty until Step 6)
```

Single elaboration parameter: **`numPins ∈ [1, 32]`** (carried in
`GpioConfig`). 32 is the hard ceiling because every per-pin field
fits in a single 32-bit APB register and the user-visible register
file is always 32 bits wide, with bits beyond `numPins`
**reserved-as-0**. For >32 pins, instantiate multiple
`GpioController`s.

The whole register file fits comfortably in a 256-byte window
(8-bit byte address) — the planned map below uses 14 of the 64
available 32-bit slots. There is plenty of headroom for Phase 3
stretch goals (atomic SET / CLR / TOG, alternate-function muxing).

## Planned register map

```
0x00 REVISION       RO   IP version (major.minor.patch), sourced from Makefile
0x04 CTRL           RW   master enable + irq enable
0x08 STATUS         RO   live status bits (TBD: e.g. any-IRQ-pending mirror)
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

Every per-pin register (everything except REVISION / CTRL / STATUS /
CFG_INFO) follows the same rule: bit `i` ↔ pin `i` for `i < numPins`;
bits `[31:numPins]` reserved-as-0.

---

## ✅ Done

- [x] **Project scaffold.** Directory tree, `Makefile`, `build.sbt`,
  `.gitignore`, `.scalafmt.conf`, `icebreaker.pcf`, top-level
  `README.md`, `AGENTS.md`, this `TODO.md`. Stub `GpioController`
  and `GpioControllerDemo` `Component`s tie every output off so
  `make` runs end-to-end and produces a flashable (but inert)
  `gen/GpioControllerDemo.bin`. A scaffold-pass `GpioControllerSim`
  in `src/sim/` runs against the stub controller so
  `make sim-controller` is green from day one — gets fleshed out
  alongside Steps 3–6. Hardware does nothing yet — the
  controller's regif and the demo's APB master FSM are both
  empty.

- [x] **Pin map locked.** uButton (pin 10) is `io_reset`
  (asynchronous active-LOW), matching the Uart and I2c projects.
  Snap-off section BTN1 (pin 18) is `io_btn` and feeds `gpio[2]`.
  On-board LEDs LEDR_N / LEDG_N (pins 11 / 37) are `io_led_blink`
  and `io_led_edge`.

---

## 🔲 Phase 0 — Foundations

### 🔲 Step 1 — `GpioConfig`

**Goal:** the by-value compile-time configuration record passed
into `GpioController` (and any future sub-block).

**File:** `src/hw/GpioConfig.scala` (already scaffolded — extend,
don't rewrite).

**Suggested fields:**
```scala
case class GpioConfig(
  numPins: Int = 8
) {
  require(numPins >= 1 && numPins <= 32, ...)
  val apbAddrWidth: Int = 8
}
```

**Design notes:**
- Keep it tiny. The single elaboration parameter is the pin count;
  every other knob (per-pin debouncing, alternate-function muxes)
  is Phase 3 stretch work and shouldn't bloat the public config in
  the meantime.
- `apbAddrWidth = 8` is fixed at the config because the planned
  register map fits in 256 bytes with room to spare.
- Don't add a `clkFreqHz` here. The controller has no time-domain
  knobs (no DDS, no bit-period); anything that needs `clkFreqHz` is
  the *demo*'s problem (e.g. the 1 Hz blink divider), not the IP's.
- Match the project's house style: doc-comments per field, `require`
  guards with descriptive error strings.

**Sim:** none — pure Scala validation; the `require`s fire at
elaboration. If you want a smoke test, an explicit `runMain` that
instantiates a few configs (default, `numPins=1`, `numPins=32`) and
prints them is fine, but not required for the step to close.

**Makefile:** no new target.

---

### 🔲 Step 2 — `Revision` (copy from `I2c`)

**Goal:** the per-IP version-number object stamped into the
`REVISION` register at offset 0x00.

**File:** `src/hw/Revision.scala` (already scaffolded — verify the
package is `gpio` and the values match the Makefile).

**Design notes:**
- Per the top-level AGENTS.md no-cross-project-deps rule, this is
  an intentional copy from `I2c/src/hw/Revision.scala`. Don't
  refactor it into a shared `common/` module.
- Defaults in the `sys.props.getOrElse(...)` calls **must stay in
  lockstep** with `REVISION_{MAJOR,MINOR,PATCH}` in the Makefile.
- `require` guards bound each component to its register field
  width (8 / 8 / 16 bits).

**Sim:** none.

**Makefile:** the `REVISION_DEFS` plumbing already exists in the
scaffolded Makefile.

---

## 🔲 Phase 1 — Controller IP

### 🔲 Step 3 — `GpioController` register file

**Goal:** the meat of the IP. Replace the stub-tied-off
`GpioController` with a real APB3-fronted register file that
actually drives the pad bus and samples it back.

**File:** `src/hw/GpioController.scala` (already scaffolded — extend
into a real implementation).

**Design notes:**

- **Use `spinal.lib.bus.regif.Apb3BusInterface`.** Mirror
  `I2cController.scala`'s setup verbatim:
  ```scala
  val busif = Apb3BusInterface(io.apb, (0x00, 256 Byte))
  ```
- **REVISION at 0x00 is mandatory.** Same `revPatch (16) →
  revMinor (8) → revMajor (8)` allocation order as Uart / I2c so
  a hex dump reads "0.1.0 → 0x0001_0000". Drive from the
  `Revision` object copied in Step 2.
- **CTRL at 0x04** carries at minimum:
  - `enable` (RW, 0): master enable. When 0, `irq` is forced
    low and write side-effects on per-pin registers are suppressed
    (reads still work, so software can probe). 1 = run.
  - Optionally `irqEnable` (RW): convenience global gate over
    the per-pin IER. The Uart / I2c convention OR-reduces
    `(ISR & IER) & CTRL.enable`; do the same and don't add a
    second redundant gate.
- **STATUS at 0x08** is a thin live-mirror; minimum useful content
  is `irq_pending` = `OR(ISR & IER)` so software can poll without
  reading ISR (which would be a no-op read but still pollutes the
  trace). Optional: `any_input_high` = `OR(IN)`.
- **ISR at 0x0C / IER at 0x10.** ISR fields are W1C with `set()`
  pulses fired by the per-pin edge-detect logic from Step 4. IER
  is plain RW. Both are 32 bits wide; bits beyond `numPins` are
  reserved-as-0.
- **DIR / IN / OUT** are the daily-driver registers:
  - `DIR(i)` → `gpio.writeEnable(i)`. 1 = output.
  - `IN(i)` ← `gpio.read(i)` (post-synchroniser, see Step 5).
  - `OUT(i)` → `gpio.write(i)`.
  Software is expected to write `DIR` before `OUT` for safe boot
  (every pin starts as input on reset).
- **OPEN_DRAIN** at 0x20 → `io.openDrain(i)`. The controller doesn't
  consume this itself; it just exposes the bit so the demo top can
  feed it into the iCE40 `SB_IO` `PIN_TYPE` parameter.
- **PULL / PULL_TYPE / INPUT_ENABLE** at 0x24 / 0x28 / 0x2C →
  identical pattern to `OPEN_DRAIN`. Pad-side concern; controller
  is a passthrough.
- **EDGE_RISE / EDGE_FALL** at 0x30 / 0x34: per-pin edge polarity
  enables for the interrupt logic in Step 4. Both bits 1 = "any
  edge". Both bits 0 = "no IRQ from this pin" — equivalent to
  `IER(i) = 0` but more readable in software.
- **CFG_INFO at 0x38**: encode `numPins` in the low 6 bits, leave
  the rest reserved-as-0. Helpful for self-discovering drivers.

- **The 32-bit-register-with-`numPins`-valid-bits convention** is
  enforceable in regif by:
  - Declaring every per-pin field as `Bits(32 bits)`.
  - On write: AND the incoming write data with
    `((1L << numPins) - 1)` before storing.
  - On read: zero-extend the stored `numPins`-wide reg to 32 bits.
  Or, equivalently, declare `Bits(numPins bits)` and have the regif
  surface zero-extend on read. Pick whichever the regif handler
  expresses most cleanly and document the choice. The
  software-visible behaviour is the same.

**IO bundle:** unchanged from the scaffold — `apb` (slave),
`gpio` (master TriStateArray), `openDrain`, `pullEnable`,
`pullType`, `inputEnable` (out Bits), `irq` (out Bool).

**Sim hints:** tested in Step 6.

**Makefile:** the `gen-controller`, `docs`, and `sim-controller`
targets already exist in the scaffolded Makefile and start working
once this step ships the entry points (`GpioControllerVerilog` and
`GpioControllerDocs` for the first two; the sim is Step 6).

**Acceptance:** `make gen-controller` produces
`gen/GpioController.v` whose APB read of REVISION returns
`0x0001_0000` (or whatever the Makefile defines), and a quick
sim writing DIR / OUT and reading IN proves the path works.

---

### 🔲 Step 4 — Per-pin edge-detect / interrupt logic

**Goal:** turn pad-input edges into sticky `ISR` bits that gate
`io.irq` through `IER` and `CTRL.enable`.

**File:** continue in `src/hw/GpioController.scala`. No new file.

**Design notes:**

- **Edge detection lives on the synchronised input.** Take the
  output of the Step-5 2-FF synchroniser (call it `inSync`) and
  register it once more; the rising edge is
  `inSync & !inSyncPrev`, the falling edge is
  `!inSync & inSyncPrev`. Per-pin, both as `Bits(numPins bits)`.
- **Mask by polarity enables.** The pulse that *sets* `ISR(i)` is
  `(rise(i) & EDGE_RISE(i)) | (fall(i) & EDGE_FALL(i))`. If both
  enables are zero the pin contributes nothing — no silent
  always-on storm.
- **W1C plumbing.** Fire `ISR.field(i).set()` on the masked edge
  pulse. Software clears by writing 1; the regif handler does the
  rest. Identical pattern to the Uart / I2c overrun bits.
- **IRQ output.** `io.irq := (ISR.asBits & IER.asBits).orR &
  CTRL.enable`. One global IRQ line; level-sensitive (stays high
  while any masked-and-unacked event is sticky), which matches
  every CPU interrupt controller this would plug into.
- **No edge-rate filter.** The 2-FF synchroniser absorbs single-cycle
  glitches; multi-cycle bus noise is the user's problem (or
  Phase 3's, if a dedicated digital filter ever lands).

**Acceptance:** sim test in Step 6 drives a short pulse on a pin
configured as input with EDGE_FALL=1, EDGE_RISE=0, IER=1, and
asserts `irq` goes high after the falling edge and stays high until
software writes 1 to `ISR(i)`.

---

### 🔲 Step 5 — Input synchroniser

**Goal:** 2-FF metastability filter on every pad-input bit before
it's exposed to `IN` or to the edge-detect logic.

**File:** continue in `src/hw/GpioController.scala`. No new file.

**Design notes:**

- **Steal `RxSync` shape from Uart.** Two cascaded `Reg(Bits)`,
  no `BufferCC` (overkill for a same-domain pad sample), each
  init'd to 0. Simulate-clean.
- **Width is `numPins`.** One synchroniser bank, indexed per pin.
- **Place between `io.gpio.read` and the rest of the design.**
  Nothing — not `IN`, not edge-detect, not anything readable from
  software — sees the unsynchronised pad bus.
- **Output enable interaction.** When `DIR(i) = 1` (output) the
  synchroniser still observes whatever the pad reads back; that's
  fine and matches every commercial GPIO block's behaviour. The
  read-back on an output pin is the loopback of your own drive —
  useful for sanity-checking pad configuration.

**Acceptance:** in sim, asserting an input edge takes 2 cycles
to appear on `IN`; the same 2-cycle pipeline-delay shows up in
`ISR` rising-edge detection.

---

### 🔲 Step 6 — `GpioControllerSim`

**Goal:** SpinalSim testbench that exercises the controller's full
APB-read / APB-write / pad-loopback / IRQ surface.

**File:** `src/sim/GpioControllerSim.scala` (already scaffolded — extend,
don't rewrite). Today the body just runs the stub through one APB read
and one APB write so `make sim-controller` is green; the real coverage
matrix below is what this step delivers.

**Suggested coverage:**

- **caseRevisionReadback** — APB read of 0x00 returns
  `(major << 24) | (minor << 16) | patch`.
- **caseDirOutLoopback** — write DIR = all 1s, write OUT = pattern,
  read IN: every bit reflects OUT (because `gpio.read` is wired
  directly to `gpio.write` in the testbench's bus model).
- **caseInputSync2Cycle** — drive `gpio.read(i)` high; assert IN
  reads 0 for one cycle, then 1.
- **caseIsrW1C** — drive a falling edge on pin 0 with
  EDGE_FALL=1 / IER=1; assert `irq` goes high; APB-write
  ISR=0x1; assert `irq` goes low.
- **caseReservedBits** — with `numPins = 4`, write
  `OUT = 0xFFFF_FFFF`; APB-read OUT returns `0x0000_000F`. Same
  for DIR / EDGE_RISE / EDGE_FALL / etc. This is the test that
  pins down the "32-bit-wide-with-reserved-tail" contract.
- **casePadConfigPassthrough** — write OPEN_DRAIN / PULL /
  PULL_TYPE / INPUT_ENABLE = pattern; assert the corresponding
  controller output ports follow the pattern bit-for-bit.

**Pad-bus model:** since the controller exposes `TriStateArray`,
the testbench maintains its own `Bits(numPins bits)` "pad" and
wires:
```scala
dut.io.gpio.read := pad
// optional loopback for caseDirOutLoopback:
when(dut.io.gpio.writeEnable(i)) { pad(i) := dut.io.gpio.write(i) }
```

**Makefile:** `sim-controller` target already declared in the
scaffolded Makefile; this step makes it stop failing.

---

## 🔲 Phase 2 — Demo

### 🔲 Step 7 — APB master FSM

**Goal:** the small hand-rolled APB master inside
`GpioControllerDemo` that configures the controller, blinks the
red LED at 1 Hz, and toggles the green LED on every debounced
falling edge of the snap-off GPIO button.

**File:** `src/hw/GpioControllerDemo.scala` (already scaffolded —
replace the APB tied-off stub with a real FSM).

**Design notes:**

- **Same SETUP / ACCESS shape as `UartEchoDemo`.** Two-phase per
  transaction; no PREADY handshake (`GpioController.PREADY` is
  hard-tied high in Step 3).
- **Boot sequence (one-shot, runs once after `BOOT` reset):**
  1. Write `DIR = 0b011` (pins 0 / 1 outputs, pin 2 input).
  2. Write `INPUT_ENABLE = 0b100` (only the button's input buffer
     is enabled — saves the LED pads' input buffers).
  3. Write `EDGE_FALL = 0b100`, `EDGE_RISE = 0b000`,
     `IER = 0b100`, `CTRL.enable = 1`.
- **Steady-state loop:**
  - A separate divider counter ticks at 1 Hz from the 12 MHz
    clock (`12_000_000` cycle period). Each tick triggers a
    read-modify-write of `OUT` to flip bit 0.
  - On every IRQ pulse from the controller, the FSM:
    1. Reads `ISR` to confirm pin 2 is the source.
    2. Read-modify-writes `OUT` to flip bit 1.
    3. Writes `0b100` to `ISR` (W1C, clears the event).
- **Counter sizing.** A 24-bit divider (`12e6` ≈ `0xB7_1B00`) gives
  the 1 Hz tick. Don't use `counter.msb` — the period changes if
  the divider width changes; explicit `=== U(11_999_999)` is the
  defensible form. Mirror the `UartController.BAUD` precedent of
  picking exact targets at elaboration.

**No new sim.** The controller's regif paths are already covered
in Step 6; the demo's value is hardware bring-up, not sim.

**Makefile:** no new target — `make` (== `make all`) already
builds this top.

---

### 🔲 Step 8 — Button debouncer

**Goal:** clean falling-edge pulses from the bouncy mechanical
button so the LED edge-toggle doesn't fire a dozen times per
press.

**Where it lives — design choice.** Two options, pick one:

1. **Debouncer in the demo, on the FSM's input path.** Mirror
   `ButtonDebouncer/src/hw/`'s timer-based debouncer (or the
   integrator variant). The controller stays a generic IP; the
   board-specific anti-bounce filter sits in the demo top, between
   `io.btn` and the controller's `gpio.read(2)`.

   - Pros: keeps `GpioController` clean. Future SoC integrations
     don't pay for debouncers on pins they wire to non-mechanical
     sources.
   - Cons: every consumer that wants debouncing has to wire it
     up themselves.

2. **Debouncer in the controller, behind a per-pin enable bit.**
   Adds a `DEBOUNCE_ENABLE` register at e.g. 0x3C with
   `numPins`-wide content; bit `i` = 1 → route pin `i` through a
   debouncer before the synchroniser. Time constant is a Phase-3
   knob (or a fixed-by-elaboration constant in `GpioConfig`).

   - Pros: every pin gets the option for free; matches some
     commercial GPIO blocks' behaviour.
   - Cons: bloats the IP; cost is paid even for pins that don't
     need it (the integrator is real LCs).

**Recommended:** Option 1 for v1 (matches the project's "small,
focused IP" ethos and the `Uart`/`I2c` precedent of keeping
board-specific filters in the demo). Revisit Option 2 in
Phase 3 if a real downstream consumer asks.

**File (Option 1):** `src/hw/Debouncer.scala`. Copy the relevant
debouncer style from `ButtonDebouncer/src/hw/`. Don't introduce
a cross-project sbt dep — copy the file and adjust the package, per
the top-level AGENTS.md rule.

**Sim:** mirror `ButtonDebouncer`'s sim. `src/sim/DebouncerSim.scala`.
Add `sim-debounce` to the Makefile.

---

### 🔲 Step 9 — `SB_IO` instantiation

**Goal:** wire the controller's per-pin sidebands (`openDrain`,
`pullEnable`, `pullType`, `inputEnable`) onto real iCE40 `SB_IO`
pad primitives so the open-drain / pull config actually takes
effect on silicon.

**File:** `src/hw/GpioControllerDemo.scala` (extend) or
`src/hw/IcePad.scala` (a small black-box wrapper around `SB_IO`,
new file).

**Design notes:**

- **iCE40 `SB_IO` parameters:**
  - `PIN_TYPE` 6'b101001 ≈ "registered output, simple input" for a
    push-pull pin. For open-drain, use a `PIN_TYPE` that lets us
    drive `D_OUT_0 = 0` for low and put the pin into `OUTPUT_TRISTATE`
    for high.
  - `PULLUP` parameter (single bit, pull-up only — iCE40 has no
    pull-down). This is the **iCE40 reality check**: the
    controller's `PULL_TYPE` register has a `pull-down` value but
    silicon can't honour it. Document this in `README.md` and the
    register-map docstrings; `pullEnable=1, pullType=0` should
    either be silently treated as "no pull" or cause a static
    elaboration error in the demo's pad mapping. Pick "silently
    no-op" so the IP stays portable to FPGAs that *do* have
    pull-downs.
- **Black-box pattern.** Use `BlackBox` for `SB_IO` rather than
  trying to coerce Spinal into emitting it; the SpinalHDL idiom is
  documented in the iCEbreaker community examples. The black box
  has `package_pin` (inout), `D_OUT_0`, `D_OUT_1`, `OUTPUT_ENABLE`,
  `D_IN_0`, `D_IN_1`, plus the `PIN_TYPE` / `PULLUP` parameters as
  generics.
- **Keep it in the demo top.** The IP itself
  (`src/hw/GpioController.scala`) stays technology-agnostic; this
  black-box wiring lives in `GpioControllerDemo` (or a sibling
  `IcePad` helper imported only by the demo). Don't put `SB_IO`
  references in `GpioController.scala`.

**Sim:** none — iCE40 black boxes don't sim. The on-board LEDs
(push-pull) and the snap-off GPIO button (input with external pull-up) are
the only pads the demo uses, so an "elaborates" smoke test plus
a hardware bring-up pass is enough.

---

### 🔲 Step 10 — Hardware bring-up

**Goal:** the gate that flips Phase 2 from 🔲 to ✅. Demo runs on
real iCEbreaker silicon end-to-end.

**Hardware bring-up checklist:**

- [ ] `make` builds without errors.
- [ ] `make flash` programs the iCEbreaker successfully.
- [ ] Red on-board LED blinks at 1 Hz (visible to the eye —
      ±10 % is fine).
- [ ] Pressing the GPIO button toggles the green LED on **release**
      (rising edge of `io_btn` on the snap-off section because the
      button is active-LOW; i.e. *falling* edge on the in-fabric
      `gpio[2]`).
- [ ] No bouncing: a single press toggles the green LED exactly
      once. If it flickers, the debouncer in Step 8 needs a
      longer time constant.
- [ ] (Optional, scope test) PMOD pin configured as open-drain
      with internal pull-up disabled; external 1 kΩ pull-up to
      3.3 V on the PMOD: line slews high in ~ns, low in ~ns,
      driven low only when the controller writes 0.

**No new sim.** Hardware-only milestone.

**Makefile:** no new target.

**Closeout:** add a 🎉 entry to `## ✅ Done` mirroring the
`Uart` and `I2c` precedents:

> - [x] **🎉 Hardware bring-up.** Red LED blinks at 1 Hz, green
>   LED toggles on every debounced press of the GPIO button.
>   Validated on iCEbreaker rev …

---

## 🔲 Phase 3 — Stretch goals (no commitment)

- [ ] **Atomic SET / CLR / TOGGLE registers.** ARM Cortex-M's GPIO
  block exposes `BSRR` (16-bit set, 16-bit clear) and an XOR
  register at separate offsets. For 32-pin parts, a 32-bit `SET`
  at e.g. 0x40, `CLR` at 0x44, `TOG` at 0x48 lets software flip
  individual pins atomically without a read-modify-write of `OUT`.
  Cheap to add (each is a write-only register that ORs / ANDs /
  XORs into `OUT`).
- [ ] **Per-pin alternate-function muxing.** A 2- or 3-bit-per-pin
  `AF` register selects between "GPIO" and N alternate
  peripheral functions (e.g. UART_TX, SPI_MOSI, I2C_SDA). Pulls
  the GPIO out of the pad's drive path when not selected. Real
  value only when this IP plugs into a SoC with multiple
  peripheral options per pad.
- [ ] **Per-pin debouncer in the IP.** Option 2 from Step 8.
  Adds a `DEBOUNCE_ENABLE` register and a configurable
  time-constant.
- [ ] **Per-pin slew-rate / drive-strength.** iCE40 doesn't
  expose these, so this is a portability-to-other-FPGAs
  feature only.
- [ ] **Multi-bank synchronous-coalesced IRQ.** Useful only when
  the controller is wired into a CPU with limited interrupt
  vectors; for a single-core SoC the per-pin ISR is already
  sufficient.

---
