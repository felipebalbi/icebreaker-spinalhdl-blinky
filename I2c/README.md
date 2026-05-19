# I2c

A from-scratch I²C **controller** and **target** in SpinalHDL, targeting
the iCEbreaker. Both halves are built in the same project so they can
be simulated against each other before either ever touches a real bus.

Status: **Phase 1 complete — `I2cController` landed.** The APB3-fronted
register-mapped wrapper (`I2cController`) is wired up — regif skeleton,
TX/RX FIFOs, 1-deep CMD shadow with a cmd-issue FSM, sticky/W1C ISR
with a masked-OR IRQ, and the runMain entrypoints for `make
gen-controller` and `make docs`. `I2cControllerSim` ships the
foundation cases (REVISION/CFG_INFO/FIFO_STATUS/PRESCALE-reset, CMD
overrun, TX underrun, single-byte register write through
`BehaviouralI2cTarget`); the remaining on-bus cases (burst, RepStart
read, addr_nack, RX back-pressure, PRESCALE retune) layer on top.
**Caveat:** the PRESCALE register is shipped *decorative* — it stores
and reads back, but `I2cBitController` consumes
`cfg.quarterPeriodCycles` at elaboration only, so writes do not retune
SCL. See `TODO.md` Step 6 "Divergences" for the plumb-through work
needed to make it take effect. Phase 0 (`I2cConfig`, `I2cIo`,
`BusTiming`), the bit-level FSM (`I2cBitController`), and the
byte-level FSM (`I2cByteController`) all have sims, including a
sim-side `BehaviouralI2cTarget` for end-to-end cases. See `TODO.md`
for the full bring-up plan.

## What's in scope

- Standard-mode (100 kHz), Fast-mode (400 kHz) and Fast-mode-Plus
  (1 MHz) bit timing, parameterised via an `I2cConfig` record and
  selected with the `BusSpeed` enum.
- 7-bit addressing, selected via the `AddrMode` enum. (10-bit is a
  stretch goal — the addressing state in the FSM is the only piece
  that needs to know, and the enum is already plumbed through.)
- Open-drain SCL/SDA modelled with Spinal's `ReadableOpenDrain`
  primitive — `write := False` pulls the line low, `write := True`
  releases it. No `writeEnable`, no way to accidentally drive a
  hard `1`.
- Clock stretching — both as a controller (sample SCL, wait if
  the target holds it low) and as a target (pull SCL low to give
  the firmware time to react).
- START / repeated-START / STOP detection on the target side.
- ACK/NACK on every byte, with ack-poll-friendly behaviour.

## What's out of scope (for now)

- Multi-master arbitration. `I2cBitController` detects arbitration
  loss spec-compliantly, `I2cByteController` surfaces it as
  `ByteRspStatus.ArbLost` + a wedge regime that the SW driver
  recovers from with `Stop` or `RepStart`, and the Phase-1
  `I2cController` regif now exposes it as `ISR.arb_lost` (sticky,
  W1C) and `STATUS.arb_lost_live`. Driving a second on-bus master
  in sim against the controller is still future work.
- High-speed mode (3.4 MHz). Same reason.
- SMBus / PMBus quirks (host notify, packet error checking, etc.).
  The FSM should be flexible enough to add these later.

## Layout

```
I2c/
  README.md          this file
  TODO.md            phased bring-up plan + design notes per block
  Makefile
  build.sbt
  icebreaker.pcf     scl/sda on PMOD1A; pull-ups external
  src/
    hw/              synthesizable code
    sim/             SpinalSim testbenches
```

## Register layout (planned)

The Phase-1 `I2cController` is APB3-fronted with the same address-map
skeleton every IP in this repo follows (see `Uart/src/hw/UartController.scala`
for the established blueprint):

```
0x00 REVISION       RO   IP version (major.minor.patch), sourced from Makefile
0x04 CTRL           RW   master enable + per-engine enables
0x08 STATUS         RO   bus_busy, cmd_busy, arb_lost_live
0x0C ISR            W1C  sticky errors / events; write 1 to clear
0x10 IER            RW   per-bit interrupt enable; matches ISR
0x14 CMD            WO   1-deep shadow register: kind, ack_out, use_txdata, byte
0x18 TXDATA         WO   push one byte into the TX-data FIFO (cfg.txFifoDepth deep)
0x1C RXDATA         RO   pop one byte from the RX-data FIFO (cfg.rxFifoDepth deep)
0x20 PRESCALE       RW   runtime override of BusTiming's quarter-period count
0x24 TX_FIFO_STATUS RO   full / empty / count / depth
0x28 RX_FIFO_STATUS RO   full / empty / count / depth
0x2C CFG_INFO       RO   bus_speed / addr_mode / use_clock_stretching / clk MHz
```

`make docs` will dump an HTML datasheet, a C header, JSON, RALF
and SystemRDL into `gen/` once `I2cController` lands (see Step 6
in `TODO.md`).

## Quickstart

Once there's something to build:

```sh
cd I2c
make           # bitstream
make sim       # run all sims
make flash     # program the iCEbreaker
```

`make help` lists every target.

## Hardware notes

- I²C is open-drain. We model each line with Spinal's
  `ReadableOpenDrain[Bool]`: `write := False` pulls the line low,
  `write := True` releases it (high-Z; the external pull-up wins).
  External 4.7 kΩ pull-ups to 3.3 V on the PMOD do the rest.
- PMOD1A pinout used here (see `icebreaker.pcf`):
  - PMOD1A.1 (FPGA pin 4) → SCL
  - PMOD1A.2 (FPGA pin 2) → SDA
- For first bring-up, an SSD1306 OLED or MCP9808 temperature sensor
  on the PMOD is a good controller-side target. A logic analyser
  on SCL/SDA is invaluable.
