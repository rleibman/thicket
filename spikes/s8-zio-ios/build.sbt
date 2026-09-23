import scala.scalanative.build.*
import scala.sys.process.*

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S8"

lazy val simSdk = Seq("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path").!!.trim
lazy val triple = "arm64-apple-ios17.0-simulator"

// Hardcoded, not read from the environment. S1 found that the sbt server captures env at
// startup; S8 found it is worse than that — sbt 2 caches the evaluated setting in its
// content-addressed store, `sys.env` is not part of the cache key, and the stale value
// therefore survives killing the server AND `touch build.sbt`. Only a real content change
// or a cleared `target/out` resets it. Flip this line to measure releaseFast.
lazy val buildMode = Mode.debug

/** S1 finding, mandatory on every iOS target: an iOS triple gives `target.os == "ios"`,
  * javalib's `LinktimeInfo.isMac` only accepts "darwin"/"macosx", and `PosixThread` then
  * calls `pthread_condattr_setclock`, which no Apple platform has. ZIO's runtime is built
  * on `java.lang.Thread`, so without this nothing here links at all.
  */
lazy val appleOsOverride = Map("scala.scalanative.meta.linktimeinfo.target.os" -> "darwin")

// sbt 2 has no `%%%`; Native artefacts are named explicitly (docs/decisions.md).
lazy val zioVersion = "2.1.26"

lazy val scalaLib = Project("scalaLib", file("target-builds/simArm64"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name                  := "s8-scala-lib",
    Compile / scalaSource := (ThisBuild / baseDirectory).value / "scala-lib" / "src" / "main" / "scala",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-source:3.9", "-no-indent"),
    libraryDependencies ++= Seq(
      "dev.zio" % s"zio_native0.5_3"         % zioVersion,
      "dev.zio" % s"zio-streams_native0.5_3" % zioVersion,
      // Not optional. `zio.Duration` IS `java.time.Duration`, and Scala Native 0.5.12's
      // javalib has no `java.time` at all (S1). Without a polyfill ZIO does not link:
      // "Unknown type java.time.Duration ... zio.Fiber$ ... zio.Runtime$". ZIO's published
      // Native artefacts do not pull this in themselves, so every downstream build has to.
      "io.github.cquiroz" % "scala-java-time_native0.5_3" % "2.7.0"
    ),
    nativeConfig := {
      val c     = nativeConfig.value
      val flags = Seq("-target", triple, "-isysroot", simSdk)
      c.withBuildTarget(BuildTarget.libraryStatic)
        .withGC(GC.immix) // the only GC that builds for iOS (S1)
        .withMode(buildMode)
        // LTO.none for fast iteration. The shipping configuration is
        // `Mode.releaseFast` + `LTO.full`, which takes the stripped app from 7.73 MB to
        // **5.75 MB** — inside N-03's 6 MB budget — and passes every test unchanged.
        // Two caveats when you switch: LTO.full needs `-lc++` at the final link (Scala
        // Native's ExceptionWrapper pulls in std::exception), and Scala Native's own
        // Validator warns that LTO.thin is unstable on Mac targets, so use full, not thin.
        .withLTO(LTO.none)
        .withTargetTriple(triple)
        .withCompileOptions(c.compileOptions ++ flags ++
          Seq("-I" + ((ThisBuild / baseDirectory).value / "shim" / "include").toString))
        .withLinkingOptions(c.linkingOptions ++ flags)
        .withLinktimeProperties(c.linktimeProperties ++ appleOsOverride)
    }
  )

lazy val root = project.in(file(".")).aggregate(scalaLib).settings(publish / skip := true)
