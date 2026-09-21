// NOTE (S5 finding): sbt 2 has no `sbt-platform-deps`, so the `%%%` operator does not
// exist. Cross-platform dependencies are therefore written with explicit artifact
// suffixes (`_sjs1_3`, `_native0.5_3`) per platform. See REPORT.md.

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S5"

val munitV = "1.3.6"
val munitScalacheckV = "1.3.1"

lazy val commonSettings = Seq(
  Compile / mainClass := Some("scalaui.signals.bench.Bench"),
  // ThreadGuard.install is process-global state, so suites must not overlap.
  Test / parallelExecution := false,
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-no-indent",
    "-source:3.9",
    "-Wunused:all",
    "-unchecked"
  )
)

lazy val signals = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("signals"))
  .settings(commonSettings)
  .settings(name := "scala-ui-signals-spike")
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"            % munitV           % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckV % Test
    )
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "org.scalameta" % s"munit_sjs1_3"            % munitV           % Test,
      "org.scalameta" % s"munit-scalacheck_sjs1_3" % munitScalacheckV % Test
    )
  )
  .nativeSettings(
    nativeConfig ~= { c =>
      c.withLTO(scala.scalanative.build.LTO.none)
        .withMode(scala.scalanative.build.Mode.releaseFast)
    },
    libraryDependencies ++= Seq(
      "org.scalameta" % s"munit_native0.5_3"            % munitV           % Test,
      "org.scalameta" % s"munit-scalacheck_native0.5_3" % munitScalacheckV % Test
    )
  )

lazy val signalsJVM    = signals.jvm
lazy val signalsJS     = signals.js
lazy val signalsNative = signals.native

lazy val root = project
  .in(file("."))
  .aggregate(signalsJVM, signalsJS, signalsNative)
  .settings(publish / skip := true)
