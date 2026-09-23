import scala.scalanative.build.*
import scala.sys.process.*

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S9"

lazy val simSdk = Seq("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path").!!.trim
lazy val triple = "arm64-apple-ios17.0-simulator"

/** S1 finding, mandatory on every iOS target: an iOS triple gives `target.os == "ios"`,
  * javalib's `LinktimeInfo.isMac` only accepts "darwin"/"macosx", and `PosixThread` then
  * calls `pthread_condattr_setclock`, which no Apple platform has.
  */
lazy val appleOsOverride = Map("scala.scalanative.meta.linktimeinfo.target.os" -> "darwin")

lazy val zioV = "2.1.26"

/** The point of this spike is to test the *shipped* bridge, so it compiles the real module
  * sources rather than copies of them. Kept as a standalone build instead of a project in
  * the root `build.sbt` because every iOS target needs `xcrun` at build-load time, which
  * would break the Linux box's build for everyone else.
  */
lazy val scalaLib = Project("scalaLib", file("target-builds/simArm64"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "s9-zio-bridge",
    Compile / unmanagedSourceDirectories := {
      val root = (ThisBuild / baseDirectory).value / ".." / ".."
      Seq(
        root / "modules" / "signals" / "shared" / "src" / "main" / "scala",
        root / "modules" / "signals" / "native" / "src" / "main" / "scala",
        root / "modules" / "renderer-api" / "shared" / "src" / "main" / "scala",
        root / "modules" / "core" / "shared" / "src" / "main" / "scala",
        root / "modules" / "effect-zio" / "shared" / "src" / "main" / "scala",
        (ThisBuild / baseDirectory).value / "scala-lib" / "src" / "main" / "scala"
      )
    },
    // The same flags the modules are built with in the root build, so this compiles the
    // real code under its real settings. If a module only survives -Werror on the JVM,
    // that is a finding rather than something to relax here.
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-source:3.9",
      "-no-indent",
      "-Wunused:all",
      // sn-bindgen's output carries unused imports in every generated object; the module
      // sources themselves are clean under these flags, which is part of what this spike
      // establishes. Silence the generated file only, rather than relaxing the flag.
      "-Wconf:src=.*generated\\.scala:s",
      "-Werror"
    ),
    libraryDependencies ++= Seq(
      "dev.zio"           % s"zio_native0.5_3"            % zioV,
      "dev.zio"           % s"zio-streams_native0.5_3"    % zioV,
      // S8: `zio.Duration` IS `java.time.Duration` and Scala Native has no `java.time`.
      "io.github.cquiroz" % "scala-java-time_native0.5_3" % "2.7.0"
    ),
    nativeConfig := {
      val c     = nativeConfig.value
      val flags = Seq("-target", triple, "-isysroot", simSdk)
      c.withBuildTarget(BuildTarget.libraryStatic)
        .withGC(GC.immix) // the only GC that builds for iOS (S1)
        .withMode(Mode.debug)
        .withLTO(LTO.none)
        .withTargetTriple(triple)
        .withCompileOptions(c.compileOptions ++ flags ++
          Seq("-I" + ((ThisBuild / baseDirectory).value / "shim" / "include").toString))
        .withLinkingOptions(c.linkingOptions ++ flags)
        .withLinktimeProperties(c.linktimeProperties ++ appleOsOverride)
    }
  )

lazy val root = project.in(file(".")).aggregate(scalaLib).settings(publish / skip := true)
