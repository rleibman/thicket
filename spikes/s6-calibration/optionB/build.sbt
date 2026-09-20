import scala.scalanative.build.*
import scala.sys.process.*

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S6"

lazy val simSdk = Seq("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path").!!.trim
// Hardcoded, per S8's finding: sbt 2 caches evaluated settings in its content-addressed
// store with `sys.env` outside the cache key, so an env-driven triple survives both a
// server restart and `rm -rf target/out` and silently builds the wrong platform. The
// device figure in REPORT.md was taken by editing this line to
// "arm64-apple-ios17.0" (and the SDK below to iphoneos), not by an env var.
lazy val triple = "arm64-apple-ios17.0-simulator"

// S1: without this java.lang.Thread does not link for an iOS triple.
lazy val appleOsOverride = Map("scala.scalanative.meta.linktimeinfo.target.os" -> "darwin")

lazy val scalaLib = Project("scalaLib", file("target-builds/simArm64"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name                  := "s6-optionb",
    Compile / scalaSource := (ThisBuild / baseDirectory).value / "scala-lib" / "src" / "main" / "scala",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-source:3.9"),
    nativeConfig := {
      val c     = nativeConfig.value
      val flags = Seq("-target", triple, "-isysroot", simSdk)
      c.withBuildTarget(BuildTarget.libraryStatic)
        .withGC(GC.immix)
        // Shipping configuration per S8: releaseFast + LTO.full, which needs -lc++ at the
        // final link. Measured here because S6 exists to compare shipped apps.
        .withMode(Mode.releaseFast)
        .withLTO(LTO.full)
        .withTargetTriple(triple)
        .withCompileOptions(c.compileOptions ++ flags ++
          Seq("-I" + ((ThisBuild / baseDirectory).value / "shim" / "include").toString))
        .withLinkingOptions(c.linkingOptions ++ flags)
        .withLinktimeProperties(c.linktimeProperties ++ appleOsOverride)
    }
  )

lazy val root = project.in(file(".")).aggregate(scalaLib).settings(publish / skip := true)
