package gpio

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._

/** iCEbreaker bring-up demo for [[GpioController]].
  *
  * Three pins are wired to physical pads:
  *
  *   - `gpio[0]` → red on-board LED (`io_led_blink`, pin 11). Driven by an
  *     APB master FSM (Step 7) that toggles its `OUT` bit at 1 Hz.
  *   - `gpio[1]` → green on-board LED (`io_led_edge`, pin 37). Toggled on
  *     every debounced falling edge of the snap-off button.
  *   - `gpio[2]` → snap-off section button BTN1 (`io_btn`, pin 18).
  *     Active-LOW. The debouncer in Step 8 consumes the synchronised
  *     pad sample and produces edge pulses.
  *
  * The on-board "uButton" (pin 10) is wired as `io_reset` instead of as a
  * GPIO input — same convention as the Uart and I2c projects, so the muscle
  * memory carries over across the repo.
  *
  * **Scaffold-only.** The body below instantiates [[GpioController]] and
  * routes the three pads, but the APB master FSM (the bit that *makes*
  * the LED blink and the edge toggle happen) is not yet written. With APB
  * tied off, `gpio.write` / `gpio.writeEnable` stay 0 and both LEDs stay
  * dark — that's expected at this milestone. The bring-up plan is in
  * `Gpio/TODO.md` Steps 7–10.
  *
  * Reset is asynchronous, active-LOW from the iCEbreaker uButton (pin 10),
  * matching the `UartEchoDemo` and `I2cControllerDemo` convention. Hold the
  * uButton to pin the controller in reset; release to run.
  *
  * @param cfg
  *   Compile-time GPIO configuration. The demo only wires three pads
  *   (LED ×2, button), so `numPins = 3` is the minimum that lights every
  *   bit in use without padding the controller out to 32 unused pins.
  */
case class GpioControllerDemo(
    cfg: GpioConfig = GpioConfig(numPins = 3)
) extends Component {

  val io = new Bundle {

    /** 12 MHz board clock (pcf maps to pin 35). */
    val clk = in Bool ()

    /** Active-LOW reset from the iCEbreaker uButton (pcf maps to pin 10).
      * Pulled high through a pull-up; shorted to ground when pressed.
      */
    val reset = in Bool ()

    /** Red on-board LED — toggled at ~1 Hz by the APB master FSM. */
    val led_blink = out Bool ()

    /** Green on-board LED — toggled on every debounced button falling edge. */
    val led_edge = out Bool ()

    /** Snap-off section push-button (BTN1 by default in pcf, active-LOW
      * with an external pull-up). Sampled into `gpio[2]` and consumed by
      * the Step-7 APB master through IN / ISR / IER.
      */
    val btn = in Bool ()
  }

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

    val ctrl = GpioController(cfg)

    // STUB: APB master FSM (Step 7) lands later. Tie inputs off so
    // elaboration succeeds — the controller will see zero traffic
    // and gpio.write / writeEnable stay 0, so the LEDs won't light
    // until the demo logic is wired up.
    ctrl.io.apb.PSEL    := B(0, 1 bits)
    ctrl.io.apb.PENABLE := False
    ctrl.io.apb.PWRITE  := False
    ctrl.io.apb.PADDR   := U(0, cfg.apbAddrWidth bits)
    ctrl.io.apb.PWDATA  := B(0, 32 bits)

    // Pad-in mapping. Only pin 2 is connected to a real pad; pins 0
    // and 1 are outputs and the controller doesn't sample its own
    // drive (Step 5 will add proper pad-loopback through SB_IO).
    val padIn = Bits(cfg.numPins bits)
    padIn      := 0
    padIn(2)   := io.btn
    ctrl.io.gpio.read := padIn

    // Pad-out mapping. Plain push-pull until Step 9 wires up SB_IO
    // open-drain / pull config from the controller's per-pin sidebands.
    val ledBlinkReg = RegNext(ctrl.io.gpio.write(0)) init (False)
    val ledEdgeReg  = RegNext(ctrl.io.gpio.write(1)) init (False)
  }

  io.led_blink := core.ledBlinkReg
  io.led_edge  := core.ledEdgeReg
}
