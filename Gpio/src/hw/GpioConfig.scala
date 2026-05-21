package gpio

/** Compile-time configuration for [[GpioController]].
  *
  * Held as a `case class` so it can be passed by value into every sub-block
  * and used to derive widths and counter constants at elaboration time.
  * Nothing in this class survives into hardware — it only shapes how the
  * hardware is built.
  *
  * The single elaboration parameter is the **number of physical GPIO pins**
  * exposed by this instance. The user-visible register file is always 32 bits
  * wide regardless: when `numPins < 32` the high `(32 - numPins)` bits in
  * every per-pin register (`DIR`, `IN`, `OUT`, `PULL`, `PULL_TYPE`, `ISR`,
  * `IER`, …) are reserved — they read as 0 and writes are ignored. This keeps
  * the software contract stable across instance widths so a 4-pin block and
  * a 32-pin block use identical drivers, register offsets, and bit shifts.
  *
  * @param numPins
  *   Number of GPIO pins this controller drives. Must be in `[1, 32]`. 32 is
  *   the hard ceiling because every per-pin register fits in a single 32-bit
  *   word; if you need more pins, instantiate a second `GpioController` and
  *   assign it a different APB base address rather than widening this knob.
  */
case class GpioConfig(numPins: Int = 8) {
  require(
    numPins >= 1 && numPins <= 32,
    s"numPins=$numPins must be in [1, 32] (one APB-mapped 32-bit register per " +
      "per-pin field; for >32 pins instantiate multiple GpioControllers)"
  )

  /** APB address-bus width in bits. The full register file fits comfortably
    * in 256 bytes (see `TODO.md` Step 3 for the planned address map), so an
    * 8-bit byte address is sufficient and matches the `Uart` / `I2c`
    * register-mapped IPs. */
  val apbAddrWidth: Int = 8
}
