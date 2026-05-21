package gpio

/** IP revision metadata for the `REVISION` register at offset 0x00.
  *
  * Single source of truth for the (major, minor, patch) version exposed in
  * [[GpioController]]'s register file. Values are read at elaboration time from
  * JVM system properties so the Makefile can stamp them in without regenerating
  * Scala source:
  *
  * {{{
  *   sbt -Drevision.major=0 -Drevision.minor=1 -Drevision.patch=0 ...
  * }}}
  *
  * The hardcoded defaults below MUST stay in lockstep with
  * `REVISION_{MAJOR,MINOR,PATCH}` in the project Makefile, so a bare `sbt
  * runMain ...` invocation outside Make still produces the canonical version.
  * If you bump the version, bump it in both places (or accept that direct sbt
  * runs will report the old number).
  *
  * Field widths match the REVISION register layout:
  *   - `major`, `minor` : 8 bits each ([31:24] and [23:16])
  *   - `patch` : 16 bits ([15:0])
  *
  * Per the project AGENTS.md we don't introduce cross-project sbt dependencies.
  * This file is an intentional copy of `I2c/src/hw/Revision.scala` (itself a
  * copy of `Uart/src/hw/Revision.scala`); only the `package` differs.
  */
object Revision {
  val major: Int = sys.props.getOrElse("revision.major", "0").toInt
  val minor: Int = sys.props.getOrElse("revision.minor", "1").toInt
  val patch: Int = sys.props.getOrElse("revision.patch", "0").toInt

  require(
    major >= 0 && major <= 0xff,
    s"revision.major=$major must fit in 8 bits (0..255)"
  )
  require(
    minor >= 0 && minor <= 0xff,
    s"revision.minor=$minor must fit in 8 bits (0..255)"
  )
  require(
    patch >= 0 && patch <= 0xffff,
    s"revision.patch=$patch must fit in 16 bits (0..65535)"
  )

  /** Pretty-printable "major.minor.patch" form, e.g. "0.1.0". */
  def asString: String = s"$major.$minor.$patch"
}
