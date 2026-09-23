import scala.scalanative.build.*
import scala.sys.process.*

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S1"

// GC and mode are environment-driven (swept rarely); the target triple is one sbt
// project per target. Two sbt traps are baked into this layout, both hit during S1:
//   1. An env var is read when the sbt *server* starts, so a stale server silently
//      builds the previous target. Hence: projects, not S1_TARGET.
//   2. Building the projects from a helper `def` makes every project share one
//      project's triple — the `:=` macro does not capture the def's parameters per
//      call. Hence: each project spelled out with literal values.
lazy val gcName = sys.env.getOrElse("S1_GC", "immix")
lazy val mode   = sys.env.getOrElse("S1_MODE", "debug")

lazy val gc = gcName match
  case "immix"  => GC.immix
  case "commix" => GC.commix
  case "boehm"  => GC.boehm
  case "none"   => GC.none
  case other    => sys.error(s"Unknown S1_GC: $other")

lazy val buildMode = mode match
  case "debug"       => Mode.debug
  case "releaseFast" => Mode.releaseFast
  case other         => sys.error(s"Unknown S1_MODE: $other")

lazy val simSdk    = Seq("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path").!!.trim
lazy val deviceSdk = Seq("xcrun", "--sdk", "iphoneos", "--show-sdk-path").!!.trim

/** Scala Native 0.5.12 bug workaround — without this, nothing that uses `java.lang.Thread`
  * links for iOS.
  *
  * An iOS triple parses to `target.os == "ios"`, and javalib's `LinktimeInfo.isMac` only
  * accepts "darwin"/"macosx". `PosixThread` then takes the non-Apple branch and calls
  * `pthread_condattr_setclock`, which no Apple platform provides, so the link fails with
  * an undefined symbol. Scala Native's own toolchain disagrees with its javalib here:
  * `Config.targetsMac` matches on the triple containing "apple" and is correctly true.
  *
  * `LinktimeValueResolver` ends with `predefined ++ conf.linktimeProperties`, so a
  * user-supplied property wins. iOS genuinely is Darwin, so this makes the two agree.
  */
lazy val appleOsOverride = Map("scala.scalanative.meta.linktimeinfo.target.os" -> "darwin")

lazy val commonSettings = Seq(
  Compile / scalaSource := (ThisBuild / baseDirectory).value / "scala-lib" / "src" / "main" / "scala",
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-source:3.9", "-no-indent"),
  nativeConfig ~= {
    _.withBuildTarget(BuildTarget.libraryStatic).withGC(gc).withMode(buildMode).withLTO(LTO.none)
  }
)

// Control: builds for macOS. Tells us whether a failure is iOS-specific or general.
lazy val host = Project("host", file("target-builds/host"))
  .enablePlugins(ScalaNativePlugin)
  .settings(commonSettings)
  .settings(name := "s1-host")

// Primary S1 target.
lazy val simArm64 = Project("simArm64", file("target-builds/simArm64"))
  .enablePlugins(ScalaNativePlugin)
  .settings(commonSettings)
  .settings(
    name := "s1-simarm64",
    nativeConfig := {
      val c     = nativeConfig.value
      // -target and -isysroot must also go in compileOptions: Scala Native ships C and
      // assembly sources of its own (GC, unwinding, libc glue) that clang compiles
      // directly, and those ignore targetTriple.
      val flags = Seq("-target", "arm64-apple-ios17.0-simulator", "-isysroot", simSdk)
      c.withTargetTriple("arm64-apple-ios17.0-simulator")
        .withCompileOptions(c.compileOptions ++ flags)
        .withLinkingOptions(c.linkingOptions ++ flags)
        .withLinktimeProperties(c.linktimeProperties ++ appleOsOverride)
    }
  )

// Only meaningful on an Intel Mac; built here to check the triple is honoured.
lazy val simX86 = Project("simX86", file("target-builds/simX86"))
  .enablePlugins(ScalaNativePlugin)
  .settings(commonSettings)
  .settings(
    name := "s1-simx86",
    nativeConfig := {
      val c     = nativeConfig.value
      val flags = Seq("-target", "x86_64-apple-ios17.0-simulator", "-isysroot", simSdk)
      c.withTargetTriple("x86_64-apple-ios17.0-simulator")
        .withCompileOptions(c.compileOptions ++ flags)
        .withLinkingOptions(c.linkingOptions ++ flags)
        .withLinktimeProperties(c.linktimeProperties ++ appleOsOverride)
    }
  )

// Out of scope to run (no iPhone available); built because a link failure is itself a finding.
lazy val device = Project("device", file("target-builds/device"))
  .enablePlugins(ScalaNativePlugin)
  .settings(commonSettings)
  .settings(
    name := "s1-device",
    nativeConfig := {
      val c     = nativeConfig.value
      val flags = Seq("-target", "arm64-apple-ios17.0", "-isysroot", deviceSdk)
      c.withTargetTriple("arm64-apple-ios17.0")
        .withCompileOptions(c.compileOptions ++ flags)
        .withLinkingOptions(c.linkingOptions ++ flags)
        .withLinktimeProperties(c.linktimeProperties ++ appleOsOverride)
    }
  )

lazy val root = project
  .in(file("."))
  .aggregate(host, simArm64)
  .settings(publish / skip := true)
