# I2c — AGENTS.md

I2c-project-specific conventions. The repo-wide rules in the
top-level `AGENTS.md` still apply; this file adds I2c-only ones.

## Status pointer

The current bring-up status lives in `TODO.md`:
- Check the `## ✅ Done` checklist near the top.
- Skim the highest-numbered `### 🔲 Step N` block before starting
  new work — the hints there are the design contract for the next
  step (Goal, Suggested IO, Design notes, Sim hints, Makefile).

Step numbering is **sequential across phases** starting at Step 1
(`I2cConfig`), not reset per phase.

The Phase-1 top, `I2cController`, is the **APB3-fronted
register-mapped wrapper** that mirrors `Uart/src/hw/UartController.scala`.
The streaming cores (`I2cBitController`, `I2cByteController`) stay
Stream-shaped; regif lives in the wrapper. See "Register-file
convention" below.

## Register-file convention (mirror Uart)

Every IP in this repo follows the same address-map skeleton at
offset 0x00, established by `UartController` and adopted by
`I2cController`:

```
0x00 REVISION       RO   IP version, sourced from the Makefile
0x04 CTRL           RW   master enable + per-engine enables
0x08 STATUS         RO   live status bits
0x0C ISR            W1C  sticky errors / events; write 1 to clear
0x10 IER            RW   per-bit interrupt enable; matches ISR
... command / data registers ...
... runtime-tunable timing register (BAUD on Uart, PRESCALE on I2c)
... per-FIFO *_FIFO_STATUS registers
... CFG_INFO        RO   build-time parameters (bus_speed, addr_mode, ...)
```

Specifics:

- **REVISION at 0x00 is mandatory.** Copy
  `Uart/src/hw/Revision.scala` into the IP's `src/hw/`, change
  the `package` line, and stamp the values in via the Makefile
  (see below). Per the top-level no-cross-project-deps rule
  this is an intentional copy, not a shared module.
- **Layout is "version-shaped":** `[31:24]=major [23:16]=minor
  [15:0]=patch`. regif allocates fields bit-0-up so declare
  `revPatch (16 bits)` → `revMinor (8)` → `revMajor (8)` to land
  at those slots. A hex dump then reads "0.1.0 → 0x0001_0000".
- **Makefile → Scala plumbing** uses JVM system properties
  (no codegen, no extra tooling). The Makefile defines
  `REVISION_{MAJOR,MINOR,PATCH}` plus
  `REVISION_DEFS := -Drevision.major=$(REVISION_MAJOR) ...` and
  redefines `SBT := sbt $(REVISION_DEFS)`. The Scala side
  reads `sys.props.getOrElse("revision.major", "0").toInt` with
  defaults that **must stay in lockstep** with the Makefile (so
  a bare `sbt runMain ...` outside Make still produces the
  canonical version).
- **ISR fields are W1C** (sticky on event-pulse `set()`, cleared
  by writing 1). `IER` is plain RW. `irq = OR(ISR & IER) &
  CTRL.enable`.
- **`make docs`** runs the controller's `*Docs` `runMain`
  entrypoint and dumps HTML / C header / JSON / RALF /
  SystemRDL into `gen/`. `make gen-controller` dumps just the
  Verilog. Both targets land alongside the controller in its
  own step.

## Open-drain primitive: `ReadableOpenDrain`, not `TriState`

Every I²C wire (`scl`, `sda`) uses
`spinal.lib.io.ReadableOpenDrain[Bool]`. **Do not** replace with
`TriState`.

Rationale:
- `ReadableOpenDrain` has only `(write, read)` — no `writeEnable`
  to forget, and you cannot accidentally drive a hard `1` because
  there is no second transistor to enable.
- Maps cleanly to an actual open-drain pad at synthesis (iCE40
  `SB_IO` open-drain mode).
- Two wires per line instead of three.

Polarity is **electrical, not logical**:
- `write := False` turns the open-drain pull-down NMOS on → pin
  tied to GND → **bus low**.
- `write := True` turns the NMOS off → pin floats → external
  pull-up wins → **bus high**.

This is why `I2cIo.releaseAll()` writes `True`, not `False`.

## `I2cIo` is the bus port

Every block that touches the bus exposes one of:
```scala
val bus = master(I2cIo())   // controller side
val bus = slave(I2cIo())    // target / observer side
```

The `IMasterSlave` mixin makes `controller.io.bus <> target.io.bus`
connect in one line — Spinal flips every nested `ReadableOpenDrain`
direction. Don't manually flip fields.

## Sim glue: `I2cIoBus`

`I2cIoBus` (in `src/sim/I2cIoSim.scala`) is the reusable wired-AND
helper for any sim that needs multiple `I2cIo` participants on
the same bus. Wiring is `bus = a.write & b.write & c.write` for
each line — anyone pulling low wins, otherwise the (modelled)
pull-up holds high.

The bus has three ports today:
- `a` — DUT (e.g. `I2cByteController` under test).
- `b` — `BehaviouralI2cTarget` (the sim-side slave model).
- `c` — competitor master, used by `caseArbLoss*` in
  `I2cByteControllerSim` to simulate a second master winning
  the bus during the address byte or mid-data-byte. Tests that
  don't need a competitor poke `competitorScl`/`competitorSda`
  to `true` (released) at setup time so port `c` stays out of
  the way.

When you need a fourth participant later (multi-target tests,
multi-master with three contenders), extend `I2cIoBus` rather
than spinning up a new helper. Existing 3-port consumers must
be updated in lockstep — `I2cBitControllerSim` doesn't use
`I2cIoBus` so it's not affected by such changes, but
`I2cIoSim`'s smoke test and `I2cByteControllerSim`'s `Rig` are.

## `I2cConfig` is the by-value compile-time record

`I2cConfig(clkFreqHz, busSpeed, addrMode, useClockStretching)` is
passed by value into every sub-block. It carries:
- `BusSpeed` enum: `Standard` (100 kHz), `Fast` (400 kHz),
  `FastPlus` (1 MHz). `busFreqHz` is derived from the enum —
  single source of truth.
- `AddrMode` enum: `SevenBits`, `TenBits` (the latter plumbed
  through but reserved for a Phase 3 stretch goal).
- `quarterPeriodCycles` derived val: I²C bit-level events fall
  on quarter-period boundaries, so this is the right unit for the
  `BusTiming` helper to consume.

The forthcoming `BusTiming` helper (Step 3) **must** consume
`quarterPeriodCycles` rather than re-derive from `clkFreqHz` /
`busFreqHz`, otherwise the rounding rule has two homes.

## Bus-shaped FSM idiom (established by `I2cBitController`)

Every FSM in this project that drives an open-drain (or any
multi-driver) bus follows this pattern. Codified during Step 4
and now a project-wide rule.

### Registered drivers, not per-state combinational drives

Bus lines come from `Reg(Bool())` regs at Component scope:

```scala
val sclDrive = Reg(Bool()) init(True)   // True = released
val sdaDrive = Reg(Bool()) init(True)
io.bus.scl.write := sclDrive
io.bus.sda.write := sdaDrive
```

States touch only the lines they change; lines that need to *hold*
the previous value just don't write. This avoids the trap of having
to re-assert every line in every state, and matches how a real bus
peripheral usually has its drive registers updated by a control FSM
rather than recomputed every cycle.

Do **not** call `io.bus.releaseAll()` at Component scope alongside
the registered drives — last-assignment-wins makes `releaseAll()`
clobber the regs, and the bus will never go low.

### Edge on entry, dwell in active

Each state owns one bus edge and one dwell:

```scala
someState.onEntry {
  someDrive    := <new value>          // the edge
  phaseCounter := timing.<spec value>  // dwell until next edge
}
someState.whenIsActive {
  when(phaseCounter === 0) { goto(nextState) }
  .otherwise              { phaseCounter := phaseCounter - 1 }
}
```

Reading the states top-to-bottom gives the bus waveform. A state
that needs neither an edge nor a dwell does not earn a state — fold
it into a neighbour.

Names end in `State` (`idleState`, `bitLowState`, `stopSdaRiseState`,
…). This is a convention, not a Spinal requirement, but it makes the
state-machine block scannable.

### Compile-time toggles via Scala `if`, not Spinal `when`

Optional features keyed off `cfg` (clock stretching, future timeouts)
gate counter logic with a Scala-time `if`:

```scala
val mayCount = if (cfg.useClockStretching) io.bus.scl.read else True
when(phaseCounter === 0) { goto(nextState) }
.elsewhen(mayCount)      { phaseCounter := phaseCounter - 1 }
```

When the toggle is `false`, `mayCount` collapses to a constant `True`
and the SCL sense path disappears entirely from the synthesised
design. A Spinal `when(cfg.useClockStretching) { ... }` would still
emit the gating logic and the sense wire — strictly worse.

## Step closeout convention

When closing a step:
1. Tick its checkbox in `## ✅ Done` (or add the line if absent).
2. Convert its `### 🔲 Step N` hint block into `### ✅ Step N`
   with a "What landed" body. Include:
   - **Files** changed / created.
   - **Divergence from the hint**, with rationale (the I2c project
     has already taken two such divergences — `BusSpeed` /
     `AddrMode` enums vs. raw fields, and `ReadableOpenDrain` vs.
     `TriState`).
   - **Sim** notes — companion file path and what it covers.
   - **Makefile** — the new `sim-<name>` target name.
3. Bump `README.md`'s status line if visible state changed.

## Cross-project deps

This project depends on **`Uart`** for debug-stream output via
`ProjectRef(file("../Uart"), "uart")` in `build.sbt`. It is
currently the project's only cross-project sbt dep, and is the
mechanism by which `I2cControllerDemo` (Step 7) pushes
TMP108-decoded ASCII over USB-UART without re-implementing a
UART core locally.

Practical implications:

- `import uart.{UartController, UartConfig}` is allowed (and
  required) inside `src/hw/I2cControllerDemo.scala` and
  `src/hw/I2cControllerDemoVerilog.scala`. No other file in
  `src/hw/` should import from the `uart` package — keep the
  surface area to the demo top.
- The `Makefile`'s `HW_SRCS` glob explicitly extends to
  `../Uart/src/hw/*.scala` so an upstream Uart edit
  invalidates `gen/I2cControllerDemo.v`. Don't drop that glob.
- `UartController`'s register layout / address map is treated
  as a stable contract; if it changes upstream, the demo's
  ROM-CPU script in `I2cControllerDemo.scala` has to be
  re-synchronised in the same commit. Keep an eye on
  `Uart/src/hw/UartController.scala`'s "Address map" doc-block
  during reviews.

This dep is sanctioned by the top-level `AGENTS.md`
("Cross-project dependencies") and `Uart/AGENTS.md` ("TX reuse
in downstream projects"). Adding any *other* cross-project sbt
dep (e.g. depending on `Pwm` or a future `Spi` project)
requires updating those two files first.

## Pin assignments

See `icebreaker.pcf`:
- PMOD1A pin 1 (FPGA pin 4) → SCL
- PMOD1A pin 2 (FPGA pin 2) → SDA
- External 4.7 kΩ pull-ups to 3.3 V are required (not on-die).

## Hardware bring-up gating

Bring-up demos (`I2cControllerDemo` once Step 7 lands,
`I2cLoopbackDemo` for Step 11) are the gate for declaring a phase
done. Don't tick a "phase complete" box on simulation alone — the
Uart project's precedent is "🎉 hardware bring-up" entries in
`TODO.md`, and the I2c project follows the same rule.
