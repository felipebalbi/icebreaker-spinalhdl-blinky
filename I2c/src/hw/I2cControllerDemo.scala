package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.fsm._

import uart.{UartController, UartConfig}

/** Phase-1 closeout demo: read TMP108 temperature on PMOD1A I²C and stream
  * decoded ASCII (`±NN.N\r\n`) to the host's USB-UART once per second.
  *
  * ==Why this exists==
  *
  * `I2cControllerSim` proves the APB → FSM → bus path in simulation
  * against a behavioural target. This demo proves the same path on real
  * silicon against a real sensor, and is the project's "🎉 hardware
  * bring-up" gate for Phase 1 (per `I2c/AGENTS.md` / "Hardware bring-up
  * gating").
  *
  * It also exercises the new repo-wide "depend on the Uart project for
  * debug-stream output" convention — `uart.UartController` is pulled in
  * via a sibling sbt `ProjectRef` and instantiated directly here. See
  * top-level `AGENTS.md` ("Cross-project dependencies") and
  * `I2c/AGENTS.md` ("Cross-project deps") for the rule.
  *
  * ==Architecture==
  *
  * Two APB3-fronted IPs (`I2cController`, `UartController`) sit behind a
  * tiny internal Apb3 fabric (`Apb3Decoder`):
  *
  * {{{
  *   ┌────────────┐     ┌─────────────┐    0x000–0x0FF   ┌────────────────┐
  *   │ ROM-CPU    │     │             │ ───────────────► │ I2cController  │
  *   │ FSM (this  │ ──► │ Apb3Decoder │                  │                │
  *   │  module)   │     │             │    0x100–0x1FF   │ UartController │
  *   └────────────┘     └─────────────┘ ───────────────► │                │
  *                                                       └────────────────┘
  * }}}
  *
  * The "ROM-CPU" is a single state machine that drives the APB master.
  * It walks a hard-coded micro-program that performs the canonical TMP108
  * temperature-register read (Start → 0x90 → 0x00 → RepStart → 0x91 →
  * byte_hi[ACK] → byte_lo[NACK] → Stop), pops the two RXDATA bytes,
  * formats them to ASCII, and pushes the seven characters into the UART
  * TX FIFO with back-pressure on `TX_FIFO_STATUS.full`.
  *
  * No interrupts are used: the FSM polls `STATUS.cmd_busy` between
  * commands and `TX_FIFO_STATUS.full` between UART pushes. The IRQ
  * outputs on both controllers are left dangling — the sim already
  * exercises that path, and adding an interrupt controller for the demo
  * doesn't earn its keep.
  *
  * ==TMP108 decode==
  *
  * `byte_hi` is the MSB (TMP108 sends MSB first). The 16-bit raw word is
  * `(byte_hi << 8) | byte_lo`, with bits[15:4] being a 12-bit
  * two's-complement temperature in units of 0.0625 °C. Bits[3:0] are
  * always zero on a TMP108.
  *
  * The format on the wire is `±NN.N\r\n` (7 bytes). One decimal digit is
  * `(rawLow4 * 10) >> 4` — a 4×4 multiply and a fixed shift, no divider
  * on the fast path. The integer °C is divided by 10 once per reading
  * (1 Hz), so the synth-time divider cost is negligible.
  *
  * @param i2cCfg
  *   Compile-time I²C config (default: 100 kHz Standard, 7-bit, no
  *   stretching — TMP108 doesn't stretch).
  * @param uartCfg
  *   Compile-time UART config (default: 115200 8N1, no flow control).
  *   Must match the host's picocom settings.
  * @param tickHz
  *   Reading rate in Hz (default: 1 Hz). Drives the free-running tick
  *   counter that gates each temperature-read pass.
  */
case class I2cControllerDemo(
    i2cCfg: I2cConfig = I2cConfig(
      clkFreqHz = 12000000,
      busSpeed = BusSpeed.Standard,
      addrMode = AddrMode.SevenBits,
      useClockStretching = false
    ),
    uartCfg: UartConfig = UartConfig(
      clkFreqHz = 12000000,
      baudRate = 115200,
      useCts = false,
      useRts = false
    ),
    tickHz: Int = 1
) extends Component {
  require(
    i2cCfg.clkFreqHz == uartCfg.clkFreqHz,
    "demo cfgs must agree on system clock"
  )

  val io = new Bundle {

    /** The board's free-running 12 MHz clock (pcf maps to pin 35). */
    val clk = in Bool ()

    /** Active-LOW reset from the iCEbreaker user button (pcf maps to pin 10).
      * The button pulls the line high through a pull-up and shorts it to
      * ground when pressed, so "reset asserted" means the pin is at 0 V.
      */
    val reset = in Bool ()

    /** SCL — open-drain bidirectional pad (pcf maps to pin 4). External
      * 4.7 kΩ pull-up to 3.3 V on the PMOD; design only ever drives low or
      * lets the line float. Generated as a flat `inout io_scl` so
      * `synth_ice40` infers an `SB_IO` open-drain primitive automatically.
      * The four-line bridge below maps `i2cCtrl.io.bus.scl.{write,read}` to
      * this pad and matches the existing `set_io io_scl 4` constraint —
      * the alternative (`master(I2cIo())`) would have flattened to
      * `io_i2c_scl_read` + `io_i2c_scl_write`, which nextpnr can't fuse
      * back into one bidirectional pin via the pcf alone.
      */
    val scl = inout(Analog(Bool()))

    /** SDA — open-drain bidirectional pad (pcf maps to pin 2). Same
      * shape as SCL above.
      */
    val sda = inout(Analog(Bool()))

    val uTx = out Bool ()
    val uRx = in Bool ()
  }

  // Build an explicit ClockDomain rather than relying on Spinal's
  // implicit default. This is the only way to wire the `reset` pin
  // into the design; without it the pin is unconnected and the user
  // button does nothing — same gotcha `Uart/UartTxDemo.scala`
  // documents at length.
  //
  //   - clockEdge        = RISING : standard for this part.
  //   - resetActiveLevel = LOW    : iCEbreaker user button (pulled
  //                                 high, grounded when pressed).
  //   - resetKind        = ASYNC  : the button isn't synchronous to
  //                                 anything; Spinal will register
  //                                 it on the way in via the default
  //                                 reset BufferCC.
  val mainClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val core = new ClockingArea(mainClockDomain) {

    // --------------------------------------------------------------------
    // Sub-IPs
    // --------------------------------------------------------------------

    val i2cCtrl = I2cController(i2cCfg)
    val uartCtrl = UartController(uartCfg)

    // ----- I²C inout pad bridge --------------------------------------
    //
    // ReadableOpenDrain.write := False means "pull low"; True means
    // "release (high-Z)". On iCE40, that maps to:
    //   - drive 0 onto the pad when write=0
    //   - leave the pad floating (analog Z) when write=1
    //   - always sample the pad back into `read`
    // synth_ice40 turns this pattern into an SB_IO open-drain
    // primitive on the constrained pin.
    when(!i2cCtrl.io.bus.scl.write) { io.scl := False }
    i2cCtrl.io.bus.scl.read := io.scl
    when(!i2cCtrl.io.bus.sda.write) { io.sda := False }
    i2cCtrl.io.bus.sda.read := io.sda

    io.uTx := uartCtrl.io.tx
    uartCtrl.io.rx := io.uRx

  // --------------------------------------------------------------------
  // Internal Apb3 fabric: one master, two slaves at 0x000 / 0x100.
  //
  // 12-bit address gives a 4 KiB region — ample for two 256-byte slaves
  // with room to grow. 32-bit data matches both controllers.
  // --------------------------------------------------------------------

  val masterApbCfg = Apb3Config(
    addressWidth = 12,
    dataWidth = 32,
    selWidth = 1,
    useSlaveError = false
  )

  val apbMaster = Apb3(masterApbCfg)

  Apb3Decoder(
    master = apbMaster,
    slaves = Seq(
      i2cCtrl.io.apb -> SizeMapping(0x000, 0x100),
      uartCtrl.io.apb -> SizeMapping(0x100, 0x100)
    )
  )

  // --------------------------------------------------------------------
  // APB master driver — minimal one-transaction-at-a-time issuer.
  //
  // The "CPU" FSM below drives `req.start`, `req.write`, `req.addr`,
  // `req.wdata`, then waits on `req.done`. `req.rdata` holds the latched
  // PRDATA from the last read. APB3 timing is the canonical 2-phase:
  // setup (PSEL=1, PENABLE=0) for one cycle, then access (PSEL=1,
  // PENABLE=1) until PREADY. Both internal slaves return PREADY=1
  // immediately, but we honour the handshake anyway so this fabric works
  // unchanged if a slow slave is ever bolted on.
  // --------------------------------------------------------------------

  val req = new Area {
    val start = Bool()        // pulse high to launch a transaction
    val write = Bool()
    val addr = UInt(12 bits)
    val wdata = Bits(32 bits)
    val done = Bool()         // high for one cycle when transaction completes
    val rdata = Bits(32 bits)
  }
  // Defaults so unconnected drivers don't latch.
  req.start := False
  req.write := False
  req.addr := 0
  req.wdata := 0

  val apbBusy = Reg(Bool()) init (False)
  val apbPhase = Reg(Bool()) init (False) // False = setup, True = access
  val apbWriteReg = Reg(Bool()) init (False)
  val apbAddrReg = Reg(UInt(12 bits)) init (0)
  val apbWdataReg = Reg(Bits(32 bits)) init (0)
  val apbRdataReg = Reg(Bits(32 bits)) init (0)
  val apbDoneReg = Reg(Bool()) init (False)

  apbDoneReg := False
  when(!apbBusy && req.start) {
    apbBusy := True
    apbPhase := False
    apbWriteReg := req.write
    apbAddrReg := req.addr
    apbWdataReg := req.wdata
  }
  when(apbBusy) {
    when(!apbPhase) {
      apbPhase := True
    }.elsewhen(apbMaster.PREADY) {
      apbBusy := False
      apbPhase := False
      apbRdataReg := apbMaster.PRDATA
      apbDoneReg := True
    }
  }

  apbMaster.PSEL := apbBusy.asBits
  apbMaster.PENABLE := apbBusy && apbPhase
  apbMaster.PWRITE := apbWriteReg
  apbMaster.PADDR := apbAddrReg
  apbMaster.PWDATA := apbWdataReg

  req.done := apbDoneReg
  req.rdata := apbRdataReg

  // --------------------------------------------------------------------
  // Register-map constants. Mirror the address tables at the top of
  // `I2cController.scala` and `Uart/src/hw/UartController.scala` — keep
  // these in sync if either layout shifts. The cross-project sbt dep
  // means an upstream Uart edit will recompile the demo, but it won't
  // automatically update these constants — review the address-map
  // doc-block on every Uart bump.
  // --------------------------------------------------------------------

  // I²C base = 0x000.
  private val I2C_CTRL = 0x004
  private val I2C_STATUS = 0x008
  private val I2C_CMD = 0x014
  private val I2C_TXDATA = 0x018
  private val I2C_RXDATA = 0x01c

  private val I2C_CTRL_ENABLE = 1 << 0
  private val I2C_CTRL_CMD_ENABLE = 1 << 1
  private val I2C_CTRL_RX_ENABLE = 1 << 2
  private val I2C_STATUS_CMD_BUSY_BIT = 1

  // CMD encoding: bits[2:0]=kind, bit[3]=ack_out.
  private val CMD_ADDR_WRITE = 0
  private val CMD_WRITE_DATA = 2
  private val CMD_READ_DATA = 3
  private val CMD_REP_START = 4
  private val CMD_STOP = 5
  private val CMD_ACK_OUT = 1 << 3 // master ACK polarity for ReadData: 0=ACK & continue, 1=NACK before Stop (matches I2cController CMD doc)

  // UART base = 0x100.
  private val UART_CTRL = 0x104
  private val UART_TXDATA = 0x114
  private val UART_TX_FIFO_STATUS = 0x120

  private val UART_CTRL_ENABLE = 1 << 0
  private val UART_CTRL_TX_ENABLE = 1 << 1
  private val UART_TX_FIFO_FULL_BIT = 0

  // TMP108 wiring constants.
  private val TMP108_ADDR_W = 0x90 // (0x48 << 1) | 0
  private val TMP108_ADDR_R = 0x91 // (0x48 << 1) | 1
  private val TMP108_REG_TEMP = 0x00

  // --------------------------------------------------------------------
  // Tick generator. `tick` pulses one cycle every clkFreqHz/tickHz
  // cycles. The CPU FSM gates a fresh read on this.
  // --------------------------------------------------------------------

  val tickPeriod = i2cCfg.clkFreqHz / tickHz
  val tickCounter = Reg(UInt(log2Up(tickPeriod) bits)) init (0)
  val tick = Bool()
  tick := False
  when(tickCounter === (tickPeriod - 1)) {
    tickCounter := 0
    tick := True
  }.otherwise {
    tickCounter := tickCounter + 1
  }

  // --------------------------------------------------------------------
  // Result registers + ASCII buffer.
  // --------------------------------------------------------------------

  val byteHi = Reg(Bits(8 bits)) init (0)
  val byteLo = Reg(Bits(8 bits)) init (0)
  // 7-byte buffer: sign, tens, ones, '.', dec, CR, LF.
  val ascii = Vec(Reg(Bits(8 bits)) init (0), 7)
  val emitIdx = Reg(UInt(3 bits)) init (0)

  // --------------------------------------------------------------------
  // ROM-CPU FSM. States are organised in linear groups; each group
  // implements one beat of the TMP108 transaction (push payload byte,
  // issue CMD, poll cmd_busy). The "poll" states re-issue STATUS reads
  // until cmd_busy clears.
  // --------------------------------------------------------------------

  val cpu = new StateMachine {
    // --- one-shot setup ---
    val sBoot: State = new State with EntryPoint
    val sCfgUart: State = new State
    val sCfgI2c: State = new State

    // --- per-reading loop ---
    val sIdle: State = new State

    // 1) AddrWrite to 0x90
    val sTxAddrW, sCmdAddrW, sPollAddrW = new State

    // 2) WriteData with the pointer byte 0x00
    val sTxPtr, sCmdWrPtr, sPollWrPtr = new State

    // 3) RepStart with 0x91
    val sTxAddrR, sCmdRepStart, sPollRepStart = new State

    // 4) ReadData, master ACK -> byteHi
    val sCmdReadHi, sPollReadHi, sPopHi = new State

    // 5) ReadData, master NACK -> byteLo
    val sCmdReadLo, sPollReadLo, sPopLo = new State

    // 6) Stop
    val sCmdStop, sPollStop = new State

    // 7) format + emit
    val sFormat, sEmitPoll, sEmitPush = new State

    // ----- helpers -------------------------------------------------
    //
    // Three patterns repeat across the FSM:
    //
    //   issueWrite(addr, data, next) : start an APB write; on done
    //                                  go to `next`.
    //   issueRead(addr, next)        : start an APB read; on done go
    //                                  to `next`. Result lands in
    //                                  `req.rdata`.
    //   pollCmdBusyClear(next)       : repeated STATUS read; advance
    //                                  to `next` only when cmd_busy
    //                                  is clear.
    //
    // Implementing them as Scala functions inside the StateMachine
    // keeps each per-state body to one or two lines.

    def issueWrite(a: Int, d: Int, next: State): Unit = {
      when(!apbBusy && !req.done) {
        req.start := True
        req.write := True
        req.addr := U(a, 12 bits)
        req.wdata := B(d, 32 bits)
      }
      when(req.done) { goto(next) }
    }

    def issueWriteBits(a: Int, d: Bits, next: State): Unit = {
      when(!apbBusy && !req.done) {
        req.start := True
        req.write := True
        req.addr := U(a, 12 bits)
        req.wdata := d.resize(32)
      }
      when(req.done) { goto(next) }
    }

    def issueRead(a: Int, next: State): Unit = {
      when(!apbBusy && !req.done) {
        req.start := True
        req.write := False
        req.addr := U(a, 12 bits)
      }
      when(req.done) { goto(next) }
    }

    // Poll STATUS.cmd_busy via repeated reads; on any read where the
    // bit is clear, advance to `next`. Otherwise re-issue.
    def pollCmdBusyClear(next: State): Unit = {
      when(!apbBusy && !req.done) {
        req.start := True
        req.write := False
        req.addr := U(I2C_STATUS, 12 bits)
      }
      when(req.done) {
        when(!req.rdata(I2C_STATUS_CMD_BUSY_BIT)) { goto(next) }
      }
    }

    // ----- setup ---------------------------------------------------

    sBoot.whenIsActive { goto(sCfgUart) }

    sCfgUart.whenIsActive {
      issueWrite(UART_CTRL, UART_CTRL_ENABLE | UART_CTRL_TX_ENABLE, sCfgI2c)
    }

    sCfgI2c.whenIsActive {
      issueWrite(
        I2C_CTRL,
        I2C_CTRL_ENABLE | I2C_CTRL_CMD_ENABLE | I2C_CTRL_RX_ENABLE,
        sIdle
      )
    }

    // ----- per-reading loop ---------------------------------------

    sIdle.whenIsActive {
      when(tick) { goto(sTxAddrW) }
    }

    // 1) push 0x90 then issue AddrWrite
    sTxAddrW.whenIsActive { issueWrite(I2C_TXDATA, TMP108_ADDR_W, sCmdAddrW) }
    sCmdAddrW.whenIsActive { issueWrite(I2C_CMD, CMD_ADDR_WRITE, sPollAddrW) }
    sPollAddrW.whenIsActive { pollCmdBusyClear(sTxPtr) }

    // 2) push 0x00 then issue WriteData
    sTxPtr.whenIsActive { issueWrite(I2C_TXDATA, TMP108_REG_TEMP, sCmdWrPtr) }
    sCmdWrPtr.whenIsActive { issueWrite(I2C_CMD, CMD_WRITE_DATA, sPollWrPtr) }
    sPollWrPtr.whenIsActive { pollCmdBusyClear(sTxAddrR) }

    // 3) push 0x91 then issue RepStart (re-aims direction via lsb=1)
    sTxAddrR.whenIsActive { issueWrite(I2C_TXDATA, TMP108_ADDR_R, sCmdRepStart) }
    sCmdRepStart.whenIsActive { issueWrite(I2C_CMD, CMD_REP_START, sPollRepStart) }
    sPollRepStart.whenIsActive { pollCmdBusyClear(sCmdReadHi) }

    // 4) ReadData with master ACK (we're going to read another byte).
    //    Per I2cController CMD doc: cmdAckOut=0 means "ACK and continue".
    sCmdReadHi.whenIsActive {
      issueWrite(I2C_CMD, CMD_READ_DATA, sPollReadHi)
    }
    sPollReadHi.whenIsActive { pollCmdBusyClear(sPopHi) }
    sPopHi.whenIsActive {
      issueRead(I2C_RXDATA, sCmdReadLo)
      when(req.done) { byteHi := req.rdata(7 downto 0) }
    }

    // 5) ReadData with master NACK (last byte). cmdAckOut=1 = "NACK before STOP".
    sCmdReadLo.whenIsActive { issueWrite(I2C_CMD, CMD_READ_DATA | CMD_ACK_OUT, sPollReadLo) }
    sPollReadLo.whenIsActive { pollCmdBusyClear(sPopLo) }
    sPopLo.whenIsActive {
      issueRead(I2C_RXDATA, sCmdStop)
      when(req.done) { byteLo := req.rdata(7 downto 0) }
    }

    // 6) Stop
    sCmdStop.whenIsActive { issueWrite(I2C_CMD, CMD_STOP, sPollStop) }
    sPollStop.whenIsActive { pollCmdBusyClear(sFormat) }

    // 7) format + emit
    //
    // The decode is purely combinational, so we run it on `onEntry`
    // and latch the seven ASCII bytes into the `ascii` Vec.
    sFormat.onEntry {
      val raw16 = (byteHi ## byteLo).asUInt
      val signed12 = raw16(15 downto 4).asSInt          // 12-bit signed (°C × 16)
      val negative = signed12 < 0
      val mag12 = Mux(negative,
        (-signed12).asUInt.resize(12),
        signed12.asUInt.resize(12))
      val intPart = (mag12 >> 4).resize(8)              // 0..127 (TMP108 range +128 / -55)
      val fracBits = mag12(3 downto 0)
      val decDigit = ((fracBits * U(10, 4 bits)) >> 4).resize(4)
      val tens = (intPart / U(10, 8 bits)).resize(4)
      val ones = (intPart - (tens * U(10, 4 bits)).resize(8))(3 downto 0)

      val zeroNibble = B"4'h3" // ASCII '0' upper nibble

      ascii(0) := Mux(negative, B('-'.toInt, 8 bits), B('+'.toInt, 8 bits))
      ascii(1) := zeroNibble ## tens.asBits
      ascii(2) := zeroNibble ## ones.asBits
      ascii(3) := B('.'.toInt, 8 bits)
      ascii(4) := zeroNibble ## decDigit.asBits
      ascii(5) := B('\r'.toInt, 8 bits)
      ascii(6) := B('\n'.toInt, 8 bits)
      emitIdx := 0
    }
    sFormat.whenIsActive { goto(sEmitPoll) }

    // Poll TX_FIFO_STATUS.full; when clear, push one byte.
    sEmitPoll.whenIsActive {
      when(!apbBusy && !req.done) {
        req.start := True
        req.write := False
        req.addr := U(UART_TX_FIFO_STATUS, 12 bits)
      }
      when(req.done) {
        when(!req.rdata(UART_TX_FIFO_FULL_BIT)) { goto(sEmitPush) }
      }
    }

    sEmitPush.whenIsActive {
      issueWriteBits(UART_TXDATA, ascii(emitIdx), sEmitPoll)
      when(req.done) {
        when(emitIdx === 6) {
          emitIdx := 0
          goto(sIdle)
        }.otherwise {
          emitIdx := emitIdx + 1
          goto(sEmitPoll)
        }
      }
    }
  }
  }
}
