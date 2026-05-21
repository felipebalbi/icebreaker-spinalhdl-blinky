# Gpio — AGENTS.md

Gpio-project-specific conventions. The repo-wide rules in the
top-level `AGENTS.md` still apply; this file adds Gpio-only ones.

## Status pointer

The current bring-up status lives in `TODO.md`:
- Check the `## ✅ Done` checklist near the top.
- Skim the highest-numbered `### 🔲 Step N` block before starting
  new work — the hints there are the design contract for the next
  step (Goal, Suggested IO, Design notes, Sim hints, Makefile).

Step numbering is **sequential across phases** starting at Step 1
(`GpioConfig`), not reset per phase.

The Phase-1 top, `GpioController`, is the **APB3-fronted
register-mapped wrapper** that mirrors `Uart/src/hw/UartController.scala`
and `I2c/src/hw/I2cController.scala`. The pad bus stays
`TriStateArray`-shaped on the controller's IO; the iCE40 `SB_IO`
mapping is the demo top's job.

## Register-file convention (mirror Uart / I2c)

Every IP in this repo follows the same address-map skeleton at
offset 0x00, established by `UartController` and adopted by
`I2cController`:

```
0x00 REVISION       RO   IP version, sourced from the Makefile
0x04 CTRL           RW   master enable + per-engine enables
0x08 STATUS         RO   live status bits
0x0C ISR            W1C  sticky errors / events; write 1 to clear
0x10 IER            RW   per-bit interrupt enable; matches ISR
... per-pin registers (DIR, IN, OUT, PULL, PULL_TYPE, ...) ...
... CFG_INFO        RO   build-time parameters (numPins, ...)
```

Specifics:

- **REVISION at 0x00 is mandatory.** `src/hw/Revision.scala` is an
  intentional copy from `I2c/src/hw/Revision.scala` (itself a copy
  from Uart) — only the `package` line differs. Per the top-level
  no-cross-project-deps rule this is *not* a shared module.
- **Layout is "version-shaped":** `[31:24]=major [23:16]=minor
  [15:0]=patch`. regif allocates fields bit-0-up so declare
  `revPatch (16 bits)` → `revMinor (8)` → `revMajor (8)` to land
  at those slots.
- **Makefile → Scala plumbing** uses JVM system properties
  (no codegen, no extra tooling). The Makefile defines
  `REVISION_{MAJOR,MINOR,PATCH}` plus
  `REVISION_DEFS := -Drevision.major=$(REVISION_MAJOR) ...` and
  redefines `SBT := sbt $(REVISION_DEFS)`. The Scala side
  reads `sys.props.getOrElse("revision.major", "0").toInt` with
  defaults that **must stay in lockstep** with the Makefile.
- **ISR fields are W1C** (sticky on event-pulse `set()`, cleared
  by writing 1). `IER` is plain RW. `irq = OR(ISR & IER) &
  CTRL.enable`.

## Per-pin registers always 32 bits, even when `numPins < 32`

Software contract is fixed: every per-pin register is 32 bits wide
with bit `i` representing pin `i` for `i < numPins`, and the upper
`(32 - numPins)` bits are **reserved-as-0**. Reads of reserved bits
return 0; writes to reserved bits are ignored. This keeps a single
driver binary working across instance widths and matches the
"firmware sees the full word, hardware ignores the unused half"
convention used by Cortex-M and RISC-V GPIO blocks.

The natural Spinal expression is `field(Bits(32 bits), …)` with
the regif handler masking writes to `numPins` bits and zeroing
unused bits on read. Don't `field(Bits(numPins bits), …)` — that
would change the software-visible address-stride / bit-mapping
when somebody re-elaborates with a different `numPins`, which is
exactly the breakage this rule prevents.

## Pad bus shape

`GpioController.io.gpio` is `master(TriStateArray(cfg.numPins bits))`:
- `write`       — value to drive when the pin is configured as output
- `writeEnable` — per-bit OE; tracks `DIR`
- `read`        — post-synchroniser pad sample, fed back into `IN`

Pad-level configuration that doesn't fit `TriStateArray` (open-drain,
pull enable / polarity, input-buffer enable) is exposed as parallel
`out Bits(cfg.numPins bits)` ports — `openDrain`, `pullEnable`,
`pullType`, `inputEnable`. The demo top maps these onto the iCE40
`SB_IO` `PIN_TYPE` and `PULLUP` parameters in Step 9.

The controller stays **technology-agnostic**: no `SB_IO`
instantiations inside `src/hw/GpioController.scala`. That's a
strict rule — keep the IP synthesisable on any FPGA fabric and let
the board-specific top pick the pad primitive.

## Reset convention

The demo (`GpioControllerDemo`) uses an **asynchronous active-LOW**
reset wired to the iCEbreaker uButton (pin 10), matching
`UartEchoDemo` and `I2cControllerDemo`. The GPIO demo button is on
the snap-off section (BTN1, pin 18 by default) — *not* the uButton.
Don't repurpose the uButton as a GPIO without flipping the
controller back to `BOOT` reset and updating the pcf and README in
the same commit.

For sims, the testbench drives `dut.clockDomain.forkStimulus(...)`
and lets Spinal handle the reset pulse — same pattern as the
`UartController` and `I2cController` sims.

## Demo APB master pattern

The Step-7 demo APB master is a small hand-rolled FSM, not a softcore.
Same shape as `UartEchoDemo` / `I2cControllerDemo`: SETUP / ACCESS
states per transaction, no PREADY handshake (the controller's
`PREADY` is hard-tied high). Sequence the demo through:

1. Configure DIR (output for pins 0/1, input for pin 2).
2. Configure PULL / PULL_TYPE / INPUT_ENABLE on pin 2.
3. Loop: every 6_000_000 cycles toggle OUT[0] (1 Hz blink).
4. Poll IN[2] (or wait on IRQ — pick one; IRQ-driven is the cleaner
   demo of the controller).
5. On each debounced falling edge of pin 2, toggle OUT[1].

The blink and edge-toggle paths share the APB; sequence them in
the FSM rather than racing them with two FSMs. `UartEchoDemo`'s
poll-then-act pattern is the reference.

## Step closeout convention

When closing a step:
1. Tick its checkbox in `## ✅ Done` (or add the line if absent).
2. Convert its `### 🔲 Step N` hint block into `### ✅ Step N`
   with a "What landed" body. Include:
   - **Files** changed / created.
   - **Divergence from the hint**, with rationale.
   - **Sim** notes — companion file path and what it covers.
   - **Makefile** — the new `sim-<name>` target name.
3. Bump `README.md`'s status line if visible state changed.

## Cross-project deps

**None.** This project does not depend on `Uart` or any other
sibling. The demo is self-contained — no UART debug stream, no
shared IP. If a future demo wants UART status output, it can pull
`Uart` in via `ProjectRef` (the same pattern `I2c` uses), and
this AGENTS.md plus `build.sbt` + `Makefile` get a "Cross-project
deps" amendment in the same commit.

## Pin assignments

See `icebreaker.pcf`:
- `io_clk` (pin 35) — 12 MHz board clock.
- `io_led_blink` (pin 11) — LEDR_N (red), driven by `gpio[0]`.
- `io_led_edge`  (pin 37) — LEDG_N (green), driven by `gpio[1]`.
- `io_reset`     (pin 10) — uButton (USR / BTN_N), active-LOW
  asynchronous reset.
- `io_btn`       (pin 18) — BTN1 on the snap-off section, sampled
  into `gpio[2]`. Active-LOW with external pull-up. Switchable to
  BTN2 (pin 19) / BTN3 (pin 20) via pcf.

## Hardware bring-up gating

Phase 2 (the demo) is the gate for declaring the project's first
milestone done. Don't tick "demo complete" on simulation alone —
the precedent across `Uart`, `I2c`, and `Pwm` is "🎉 hardware
bring-up" entries in `TODO.md`, and Gpio follows the same rule.
