package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.regif._
import spinal.lib.bus.regif.AccessType._

case class I2cController(
    cfg: I2cConfig = I2cConfig(
      clkFreqHz = 12000000,
      busSpeed = BusSpeed.Standard,
      addrMode = AddrMode.SevenBits,
      useClockStretching = true
    )
) extends Component {
  val apb3Config = Apb3Config(
    addressWidth = 8,
    dataWidth = 32,
    selWidth = 1,
    useSlaveError = false
  )

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val bus = master(I2cIo()) // open-drain SCL/SDA
    val irq = out Bool () // OR(ISR & IER) when CTRL.enable = 1
  }

  val byteCtrl = I2cByteController(cfg)
  byteCtrl.io.bus <> io.bus

  // ----- FIFOs --------------------------------------------------
  //
  // TX and RX are separate StreamFifos sized by cfg.{tx,rx}FifoDepth.
  // Same shape as UartController: Stream-shaped cores slot in with
  // no handshake gymnastics, and the asymmetry (TX-heavy bursts vs
  // RX-heavy reads) is sized at synth time.
  //
  // TX FIFO carries every on-wire payload byte (address byte for
  // AddrWrite/AddrRead, address+R/W byte for RepStart, each
  // WriteData byte) -- see the CMD doc-comment and TODO.md design
  // notes for the split-vs-inline-byte rationale.
  //
  // RX FIFO is pushed by the cmd-issue FSM further down (on a
  // successful ReadData rsp, gated by ctrlRxEnable) and popped via
  // RXDATA reads.
  val txFifo = StreamFifo(Bits(8 bits), cfg.txFifoDepth)
  val rxFifo = StreamFifo(Bits(8 bits), cfg.rxFifoDepth)

  // ----- regif --------------------------------------------------
  val busif = Apb3BusInterface(io.apb, (0x00, 256 Byte))

  // 0x00 REVISION ------------------------------------------------
  // Layout: [31:24]=major [23:16]=minor, [15:0]=patch
  // regif allocates bit-0-up so declare patch -> minor -> major.
  val REVISION =
    busif.newReg(doc = "IP revision (major.minor.patch), read-only")
  val revPatch = REVISION.field(UInt(16 bits), RO, doc = "Patch [15:0]")
  val revMinor = REVISION.field(UInt(8 bits), RO, doc = "Minor [23:16]")
  val revMajor = REVISION.field(UInt(8 bits), RO, doc = "Major [31:24]")
  revPatch := U(Revision.patch, 16 bits)
  revMinor := U(Revision.minor, 8 bits)
  revMajor := U(Revision.major, 8 bits)

  // 0x04 CTRL --------------------------------------------------
  val CTRL = busif.newReg(doc = "Control register")
  val ctrlEnable = CTRL.field(
    Bool(),
    RW,
    0,
    doc = "Master enable. 0 = block IRQ + freeze byte-controller. 1 = run."
  )
  val ctrlCmdEnable =
    CTRL.field(Bool(), RW, 0, doc = "Drain CMD shadow into byte-controller.")
  val ctrlRxEnable = CTRL.field(
    Bool(),
    RW,
    0,
    doc = "Push read bytes into RX FIFO. 0 = drop them."
  )
  val ctrlStretchEnable: Bool =
    if (cfg.useClockStretching)
      CTRL.field(Bool(), RW, 1, doc = "Honour clock-stretching path.")
    else {
      // Reserve the bit so the address map matches across cfg variants,
      // but tie it to 0 - there is no stretch path to gate.
      CTRL.field(
        Bool(),
        RO,
        0,
        doc = "Reserved (cfg.useClockStretching=false)."
      ) := False
      False
    }

  // 0x08 STATUS --------------------------------------------------
  val STATUS = busif.newReg(doc = "Status register")
  val statusBusBusy = STATUS.field(Bool(), RO, 0, doc = "Controller is busy.")
  val statusCmdBusy = STATUS.field(
    Bool(),
    RO,
    0,
    doc =
      "A Command is queue or in flight; Software must wait for this to fall before writing CMD again."
  )
  val statusArbLost =
    STATUS.field(Bool(), RO, 0, doc = "Arbitration loss (W1C copy in ISR).")

  // statusBusBusy / statusCmdBusy / statusArbLost are driven from
  // the cmd-issue FSM further down (the source signals don't exist
  // until txFifo/rxFifo/cmd-shadow are wired in).

  // 0x0c ISR --------------------------------------------------
  val ISR = busif.newReg(doc = "Interrupt Status Register")
  val isrAddrNack =
    ISR.field(Bool(), W1C, 0, doc = "Target NACKed address phase.")
  val isrDataNack = ISR.field(Bool(), W1C, 0, doc = "Target NACKed data phase.")
  val isrArbLost =
    ISR.field(Bool(), W1C, 0, doc = "If set, then we lost arbitration.")
  val isrStretchTimeout = ISR.field(
    Bool(),
    RO,
    0,
    doc = "Reserved for future use. Currently tied to 0."
  )
  val isrCmdDone =
    ISR.field(Bool(), W1C, 0, doc = "Command retired (byte-ctrl finished).")
  val isrCmdOverrun = ISR.field(
    Bool(),
    W1C,
    0,
    doc = "Command was written while CMD_BUSY=1; the new write was dropped."
  )
  val isrRxDone =
    ISR.field(Bool(), W1C, 0, doc = "A byte was pushed into the RX FIFO.")
  val isrTxUnderrun = ISR.field(
    Bool(),
    W1C,
    0,
    doc = "CMD needed a TXDATA byte but TXDATA was empty; the CMD was dropped."
  )

  // Sticky-event triggers from byteCtrl. Each W1C bit latches on the
  // one-cycle pulse below and stays set until firmware writes 1 to
  // clear it. Same idiom as UartController:
  //   when(rxCore.io.framingError) { isrFraming.set() }
  //
  // arb_lost: byteCtrl publishes ArbLost as a status field on the
  // response stream — assert on the cycle the response is consumed
  // (rsp.fire is a one-cycle pulse, perfect for a sticky latch).
  val inFlightKind = Reg(ByteCmdKind()) init (ByteCmdKind.Stop)
  when(byteCtrl.io.cmd.fire) { inFlightKind := byteCtrl.io.cmd.payload.kind }

  def inAddrPhase = inFlightKind === ByteCmdKind.AddrWrite ||
    inFlightKind === ByteCmdKind.AddrRead ||
    inFlightKind === ByteCmdKind.RepStart

  def inDataPhase = inFlightKind === ByteCmdKind.WriteData

  // ackIn polarity from I2cByteController.ByteRsp: False = ACK
  // (target accepted), True = NAK (target refused). Per-phase
  // routing splits NAK-during-address from NAK-during-data so
  // firmware can recover differently (re-address vs retry payload).
  when(
    byteCtrl.io.rsp.fire && byteCtrl.io.rsp.payload.status === ByteRspStatus.Ok
  ) {
    when(inAddrPhase && byteCtrl.io.rsp.payload.ackIn) { isrAddrNack.set() }
    when(inDataPhase && byteCtrl.io.rsp.payload.ackIn) { isrDataNack.set() }
  }
  when(
    byteCtrl.io.rsp.fire &&
      byteCtrl.io.rsp.payload.status === ByteRspStatus.ArbLost
  ) {
    isrArbLost.set()
  }
  when(byteCtrl.io.rsp.fire) {
    isrCmdDone.set()
  }
  // rx_done mirrors UartController: fire on a successful FIFO push.
  // The push itself is gated in the cmd-issue block below (only
  // ReadData rsps with Ok status push, and only when ctrlRxEnable
  // is set), so this fires exactly when a real byte landed.
  when(rxFifo.io.push.fire) { isrRxDone.set() }


  // 0x10 IER --------------------------------------------------
  // Mirrors the ISR layout bit-for-bit so firmware can mask events
  // by writing the same bit positions it just read from ISR. Bit 3
  // (stretch_timeout) is reserved RO 0 to match ISR's reservation
  // — the mask slot is preserved for the future stretch-timeout
  // event without renumbering the rest.
  val IER = busif.newReg(doc = "Interrupt enable (mask, RW)")
  val ierAddrNack =
    IER.field(Bool(), RW, 0, doc = "Enable address-NACK interrupt.")
  val ierDataNack =
    IER.field(Bool(), RW, 0, doc = "Enable data-NACK interrupt.")
  val ierArbLost =
    IER.field(Bool(), RW, 0, doc = "Enable arbitration-lost interrupt.")
  val ierStretchTimeout = {
    // Reserved to keep the bit position aligned with ISR.stretch_timeout.
    val f = IER.field(
      Bool(),
      RO,
      0,
      doc = "Reserved (matches ISR.stretch_timeout slot)."
    )
    f := False
    f
  }
  val ierCmdDone =
    IER.field(Bool(), RW, 0, doc = "Enable CMD-done interrupt.")
  val ierCmdOverrun =
    IER.field(Bool(), RW, 0, doc = "Enable CMD-overrun interrupt.")
  val ierRxDone =
    IER.field(Bool(), RW, 0, doc = "Enable RX-done interrupt.")
  val ierTxUnderrun =
    IER.field(Bool(), RW, 0, doc = "Enable TX-underrun interrupt.")

  // 0x14 CMD --------------------------------------------------
  // 1-deep WO shadow register (NOT a FIFO). Firmware polls
  // STATUS.cmd_busy (or waits for ISR.cmd_done) between writes.
  // Writes while cmd_busy = 1 are silently dropped and
  // ISR.cmd_overrun is set. The opcode/ack_out land here; every
  // on-wire payload byte is sourced from TXDATA.
  val CMD = busif.newReg(doc = "Byte-command shadow register (write-only)")
  val cmdKind = CMD.field(
    UInt(3 bits),
    WO,
    doc =
      "Command kind: 0=AddrWrite 1=AddrRead 2=WriteData 3=ReadData 4=RepStart 5=Stop."
  )
  val cmdAckOut = CMD.field(
    Bool(),
    WO,
    doc =
      "Master ACK polarity for ReadData: 0 = ACK and continue, 1 = NACK before STOP."
  )

  // 0x18 TXDATA --------------------------------------------------
  // WO byte-push port for the TX-data FIFO. Sized by cfg.txFifoDepth.
  // Every on-wire payload byte (address byte for AddrWrite/AddrRead,
  // address+R/W byte for RepStart, each WriteData byte) is sourced
  // from this FIFO. Doc-only here; the actual push is detected
  // against the register address in the wiring pass.
  val TXDATA = busif.newReg(doc = "Write to push a byte into the TX FIFO.")
  val txDataWord = TXDATA.field(
    Bits(8 bits),
    WO,
    doc = "Byte to enqueue. Pushed into TX FIFO on write hit."
  )

  // 0x1C RXDATA --------------------------------------------------
  // RO byte-pop port for the RX-data FIFO. Sized by cfg.rxFifoDepth.
  // Read returns the front of the RX FIFO and pops it (returns 0 if
  // RX_FIFO_STATUS.empty = 1). Doc-only here; the pop is detected
  // against the register address in the wiring pass.
  val RXDATA = busif.newReg(doc = "Read to pop a byte from the RX FIFO.")
  val rxDataWord = RXDATA.field(
    Bits(8 bits),
    RO,
    doc =
      "Front byte of the RX FIFO. Reading this register pops the FIFO; reads while RX_FIFO_STATUS.empty = 1 return zero."
  )
  rxDataWord := rxFifo.io.pop.payload

  // 0x20 PRESCALE --------------------------------------------------
  // Runtime override of BusTiming. Reset value = cfg.quarterPeriodCycles
  // so a bare reset reproduces the build-time-configured SCL frequency.
  // Firmware can re-tune SCL after the fact by writing this register
  // (the same role BAUD plays for the UART).
  //
  // *** DECORATIVE TODAY *** — I2cBitController currently consumes
  // cfg.quarterPeriodCycles at elaboration time and exposes no
  // runtime input, so writes to this register change the stored
  // value but do NOT re-tune SCL. The register stays in the map so
  // the address layout matches the Step-6 contract; making it take
  // effect needs I2cBitController.io.prescale + a hardware
  // BusTiming-equivalent + plumbing through I2cByteController. See
  // I2c/TODO.md Step 6 design notes.
  val PRESCALE = busif.newReg(doc = "Quarter-period cycle count for BusTiming")
  val prescale = PRESCALE.field(
    UInt(16 bits),
    RW,
    BigInt(cfg.quarterPeriodCycles),
    doc =
      "Quarter-period in system-clock cycles. Reset = cfg.quarterPeriodCycles. Decorative in v0.1 — wiring through to BusTiming is a follow-on."
  )

  // 0x24 TX_FIFO_STATUS --------------------------------------------------
  // Layout (identical to RX_FIFO_STATUS):
  //   [0]      full   - push.ready is low
  //   [1]      empty  - pop.valid is low (no byte queued)
  //   [7:2]    reserved
  //   [15:8]   count  - live occupancy (0..depth)
  //   [23:16]  depth  - synth-time capacity (cfg.txFifoDepth)
  val TX_FIFO_STATUS = busif.newReg(doc = "TX FIFO status (read-only)")
  val txFifoFull = TX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc = "TX FIFO is full; further TXDATA writes are dropped silently."
  )
  val txFifoEmpty = TX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc = "TX FIFO is empty (no payload byte staged)."
  )
  TX_FIFO_STATUS.reserved(6 bits)
  val txFifoCount = TX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Bytes currently queued in the TX FIFO (0..txFifoDepth)."
  )
  val txFifoDepth = TX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Synth-time TX FIFO capacity in bytes (= cfg.txFifoDepth)."
  )
  txFifoFull := !txFifo.io.push.ready
  txFifoEmpty := !txFifo.io.pop.valid
  txFifoCount := txFifo.io.occupancy.resize(8 bits)
  txFifoDepth := U(cfg.txFifoDepth, 8 bits)

  // 0x28 RX_FIFO_STATUS --------------------------------------------------
  val RX_FIFO_STATUS = busif.newReg(doc = "RX FIFO status (read-only)")
  val rxFifoFull = RX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc = "RX FIFO is full; the next received byte will trigger an overrun."
  )
  val rxFifoEmpty = RX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc =
      "RX FIFO is empty; firmware should poll for !empty before reading RXDATA."
  )
  RX_FIFO_STATUS.reserved(6 bits)
  val rxFifoCount = RX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Bytes currently queued in the RX FIFO (0..rxFifoDepth)."
  )
  val rxFifoDepth = RX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Synth-time RX FIFO capacity in bytes (= cfg.rxFifoDepth)."
  )
  rxFifoFull := !rxFifo.io.push.ready
  rxFifoEmpty := !rxFifo.io.pop.valid
  rxFifoCount := rxFifo.io.occupancy.resize(8 bits)
  rxFifoDepth := U(cfg.rxFifoDepth, 8 bits)

  // 0x2C CFG_INFO --------------------------------------------------
  // Read-only window onto the synth-time configuration so firmware
  // can fingerprint the build it's talking to. Layout per the
  // address map in TODO.md:
  //   [1:0]    bus_speed           0=Std 1=Fast 2=Fast+
  //   [2]      addr_mode           0=7-bit 1=10-bit
  //   [3]      use_clock_stretching
  //   [15:4]   reserved
  //   [23:16]  clk_freq_mhz        clkFreqHz / 1_000_000 (truncated)
  val busSpeedCode = cfg.busSpeed match {
    case BusSpeed.Standard => 0
    case BusSpeed.Fast     => 1
    case BusSpeed.FastPlus => 2
  }
  val addrModeCode = cfg.addrMode match {
    case AddrMode.SevenBits => 0
    case AddrMode.TenBits   => 1
  }

  val CFG_INFO = busif.newReg(doc = "Build-time configuration (read-only)")
  val cfgInfoBusSpeed = CFG_INFO.field(
    UInt(2 bits),
    RO,
    doc = "Bus speed: 0 = Standard, 1 = Fast, 2 = Fast+."
  )
  val cfgInfoAddrMode =
    CFG_INFO.field(
      UInt(1 bits),
      RO,
      doc = "Address mode: 0 = 7-bit, 1 = 10-bit."
    )
  val cfgInfoUseStretch = CFG_INFO.field(
    Bool(),
    RO,
    doc = "1 = clock-stretching path is wired in (cfg.useClockStretching)."
  )
  CFG_INFO.reserved(12 bits)
  val cfgInfoClkFreqMhz = CFG_INFO.field(
    UInt(8 bits),
    RO,
    doc = "Synth clock in MHz (clkFreqHz / 1_000_000, truncated)."
  )

  cfgInfoBusSpeed := U(busSpeedCode, 2 bits)
  cfgInfoAddrMode := U(addrModeCode, 1 bits)
  cfgInfoUseStretch := Bool(cfg.useClockStretching)
  cfgInfoClkFreqMhz := U(cfg.clkFreqHz / 1000000, 8 bits)

  // ----- TXDATA push glue -------------------------------------------------
  //
  // Plain WO field in the register file (so it shows up in the
  // datasheet) but the *effect* is hand-rolled: any write that hits
  // TXDATA's address pulses txFifo.io.push.valid for one cycle.
  // Same pattern as UartController. The byte comes off
  // busif.writeData rather than the stored field value because the
  // field updates one cycle late.
  val txDataWriteHit =
    busif.doWrite && (busif.writeAddress() === U(
      TXDATA.getAddr(),
      busif.writeAddress().getWidth bits
    ))
  txFifo.io.push.valid := txDataWriteHit
  txFifo.io.push.payload := busif.writeData(7 downto 0)

  // ----- RXDATA pop glue --------------------------------------------------
  //
  // RXDATA reads pop one byte from the RX FIFO. Reads while empty
  // return zero (rxFifo.io.pop.payload is X-but-Spinal-models-as-0
  // when valid=0). The pop only fires when there's actually a byte
  // staged so we don't deassert pop.valid spuriously.
  val rxDataReadHit =
    busif.doRead && (busif.readAddress() === U(
      RXDATA.getAddr(),
      busif.readAddress().getWidth bits
    ))
  rxFifo.io.pop.ready := rxDataReadHit && rxFifo.io.pop.valid

  // ----- CMD shadow + cmd-issue FSM --------------------------------------
  //
  // CMD is a 1-deep shadow register, NOT a FIFO. Firmware polls
  // STATUS.cmd_busy (or waits for ISR.cmd_done) between writes.
  // Writes while cmd_busy = 1 are silently dropped and
  // ISR.cmd_overrun is set. See TODO.md design notes for the
  // why-not-a-FIFO rationale.
  //
  // Two flags pin down the "is the controller busy?" question
  // unambiguously:
  //   - cmdShadowValid : a freshly-latched CMD is waiting to be
  //                      handed to byteCtrl (still in the shadow).
  //   - cmdInFlight    : byteCtrl has accepted the CMD and is
  //                      working on it; we're waiting for the rsp.
  // STATUS.cmd_busy = OR of these. Firmware's "the CMD has
  // retired" gate is cmd_busy falling, which only happens on
  // rsp.fire or on the underrun-drop path.
  val cmdShadowValid = Reg(Bool()) init (False)
  val cmdInFlight = Reg(Bool()) init (False)
  val cmdKindReg = Reg(ByteCmdKind()) init (ByteCmdKind.Stop)
  val cmdAckOutReg = Reg(Bool()) init (True)
  val cmdBusy = cmdShadowValid || cmdInFlight

  // CMD address-hit. Decode the kind off busif.writeData on the
  // hit cycle (the stored field value updates one cycle late).
  val cmdWriteHit =
    busif.doWrite && (busif.writeAddress() === U(
      CMD.getAddr(),
      busif.writeAddress().getWidth bits
    ))
  val cmdWriteKind = busif.writeData(2 downto 0).asUInt
  val cmdWriteAckOut = busif.writeData(3)

  when(cmdWriteHit) {
    when(cmdBusy) {
      // Overrun: drop the write, latch the sticky bit. The byte-ctrl
      // sees nothing.
      isrCmdOverrun.set()
    } otherwise {
      // Latch into the shadow. The cmd-issue path below decides
      // when to actually drive byteCtrl.io.cmd.
      cmdShadowValid := True
      switch(cmdWriteKind) {
        is(0) { cmdKindReg := ByteCmdKind.AddrWrite }
        is(1) { cmdKindReg := ByteCmdKind.AddrRead }
        is(2) { cmdKindReg := ByteCmdKind.WriteData }
        is(3) { cmdKindReg := ByteCmdKind.ReadData }
        is(4) { cmdKindReg := ByteCmdKind.RepStart }
        is(5) { cmdKindReg := ByteCmdKind.Stop }
        // 6/7 reserved: treat as Stop so a bogus opcode degrades to
        // "release the bus" rather than something random.
        default { cmdKindReg := ByteCmdKind.Stop }
      }
      cmdAckOutReg := cmdWriteAckOut
    }
  }

  // Per the CMD doc-comment / TODO.md split-vs-inline rationale:
  // AddrWrite, AddrRead, RepStart, WriteData each consume one
  // TXDATA byte on issue. ReadData and Stop do not.
  val cmdNeedsPayload =
    cmdKindReg === ByteCmdKind.AddrWrite ||
      cmdKindReg === ByteCmdKind.AddrRead ||
      cmdKindReg === ByteCmdKind.WriteData ||
      cmdKindReg === ByteCmdKind.RepStart

  // ReadData gates on RX FIFO space only when ctrlRxEnable is
  // set — see TODO.md "Why no rx_overrun": full FIFO leaves the
  // CMD parked in the shadow (no error bit, SCL stays idle, no
  // byte ever lost). When ctrlRxEnable=0, bytes are dropped per
  // spec and the gate disappears.
  val cmdNeedsRxSpace =
    cmdKindReg === ByteCmdKind.ReadData && ctrlRxEnable
  val payloadAvail = txFifo.io.pop.valid
  val rxSpaceAvail = rxFifo.io.push.ready

  // The drive into byteCtrl. ctrlEnable freezes the whole pipeline
  // (mirrors UartController's CTRL.enable). ctrlCmdEnable gates
  // only the cmd-issue path so firmware can drain or pause without
  // killing the rest of the controller.
  val issueGate = ctrlEnable && ctrlCmdEnable && cmdShadowValid

  // Underrun: a payload-needing CMD landed in the shadow but TXDATA
  // is empty. Drop the CMD (clear the shadow), latch the sticky
  // bit, and do NOT poke byteCtrl. Symmetric with cmd_overrun.
  val underrunDrop = issueGate && cmdNeedsPayload && !payloadAvail
  when(underrunDrop) {
    isrTxUnderrun.set()
    cmdShadowValid := False
  }

  // Normal issue path: present the CMD to byteCtrl when the shadow
  // is loaded, payload (if needed) is staged, and RX has space (if
  // needed). Anything that fails one of these stays parked.
  val canIssue =
    issueGate &&
      !underrunDrop &&
      (!cmdNeedsPayload || payloadAvail) &&
      (!cmdNeedsRxSpace || rxSpaceAvail)

  byteCtrl.io.cmd.valid := canIssue
  byteCtrl.io.cmd.payload.kind := cmdKindReg
  byteCtrl.io.cmd.payload.ackOut := cmdAckOutReg
  // data field is don't-care for ReadData/Stop; for payload kinds
  // we route TXDATA's front through. Driving it unconditionally
  // means no Mux on the byte-ctrl side and no warning from Spinal.
  byteCtrl.io.cmd.payload.data := txFifo.io.pop.payload

  // Pop one TXDATA byte exactly when the byte-ctrl accepts a
  // payload-needing CMD. The pop is one-cycle (cmd.fire is a pulse).
  txFifo.io.pop.ready := byteCtrl.io.cmd.fire && cmdNeedsPayload

  // Shadow → in-flight transition.
  when(byteCtrl.io.cmd.fire) {
    cmdShadowValid := False
    cmdInFlight := True
  }

  // ----- response handling ----------------------------------------------
  //
  // We never backpressure rsps (no reason to — they're single-beat
  // and we consume them combinationally). rsp.fire is therefore the
  // same as rsp.valid, but the Stream contract still wants ready
  // asserted so we don't gum up the bit-controller.
  byteCtrl.io.rsp.ready := True

  // RX FIFO push: a successful ReadData rsp lands here. Gated by
  // ctrlRxEnable per spec: with rx_enable=0, received bytes are
  // dropped on the floor (and the issue path above didn't gate on
  // RX space, so the wire still ran). push.ready is guaranteed
  // True here because the issue path gated ReadData on it before
  // letting the CMD fire — see canIssue above.
  rxFifo.io.push.valid :=
    byteCtrl.io.rsp.fire &&
      inFlightKind === ByteCmdKind.ReadData &&
      byteCtrl.io.rsp.payload.status === ByteRspStatus.Ok &&
      ctrlRxEnable
  rxFifo.io.push.payload := byteCtrl.io.rsp.payload.data

  // In-flight → idle transition.
  when(byteCtrl.io.rsp.fire) {
    cmdInFlight := False
  }

  // ----- STATUS bits -----------------------------------------------------
  //
  // cmd_busy: any unretired CMD, whether parked in the shadow or
  // being processed by byteCtrl. Falls on rsp.fire (or underrun-drop).
  // bus_busy: best functional proxy for "controller mid-transaction".
  // The byte-controller doesn't expose a live "in a transaction"
  // signal; cmdBusy is the closest faithful approximation given
  // the 1-deep-shadow semantics.
  // arb_lost_live: the spec asks for a live mirror of the arb-loss
  // line. The byte-controller only publishes ArbLost via rsp.status
  // (one-cycle), so the most useful thing we can expose here is a
  // sticky mirror of ISR.arb_lost — firmware sees the same bit
  // until it W1C-clears the ISR.
  statusBusBusy := cmdBusy
  statusCmdBusy := cmdBusy
  statusArbLost := isrArbLost

  // ----- IRQ aggregation -------------------------------------------------
  //
  // OR(ISR & IER) gated by the master enable. Mirrors UartController.
  // isrStretchTimeout is tied to RO 0 today (reserved for future
  // stretch-timeout support); the term is folded in for layout
  // symmetry with ISR/IER and collapses away at synth.
  val irqRaw =
    (isrAddrNack & ierAddrNack) |
      (isrDataNack & ierDataNack) |
      (isrArbLost & ierArbLost) |
      (isrStretchTimeout & ierStretchTimeout) |
      (isrCmdDone & ierCmdDone) |
      (isrCmdOverrun & ierCmdOverrun) |
      (isrRxDone & ierRxDone) |
      (isrTxUnderrun & ierTxUnderrun)
  io.irq := irqRaw && ctrlEnable
}
