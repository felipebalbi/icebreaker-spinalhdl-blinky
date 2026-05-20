ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "org.example"

val spinalVersion = "1.14.1"

val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin(
  "com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion
)

// Cross-project debug-IO dep: pull in the Uart project so this
// build (and any future debug-stream consumer in the I2c crate)
// can instantiate `uart.UartController` directly. See top-level
// AGENTS.md ("Cross-project sbt dependencies are permitted only
// for shared debug/IO IPs") and I2c/AGENTS.md ("Cross-project deps").
lazy val uart = ProjectRef(file("../Uart"), "uart")

lazy val i2c = (project in file("."))
  .settings(
    name := "i2c",
    Compile / scalaSource := baseDirectory.value / "src",
    libraryDependencies ++= Seq(
      spinalCore,
      spinalLib,
      spinalIdslPlugin,
      "com.github.spinalhdl" %% "spinalhdl-sim" % spinalVersion
    )
  )
  .dependsOn(uart)

fork := true
