import scala.sys.process.*

// Thicket — see docs/. Toolchain policy: latest stable Scala and sbt (docs/decisions.md).
// sbt 2 has no `%%%`, so cross-platform test deps carry explicit artefact suffixes.

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.thicket"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / licenses     := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

val zioV        = "2.1.26"

/** Coverage settings, applied to every module that has JVM tests.
  *
  * **The number only covers the effect-free core**, and that is worth stating plainly
  * rather than quoting an aggregate that sounds worse than it is. The GTK, Android and
  * Apple renderers are Scala Native or ART code exercised by self-tests that drive the real
  * toolkit in a real process; scoverage instruments neither, so those modules contribute
  * nothing here and their absence is not a gap in testing. `docs/12` §12.9 says which is
  * which.
  *
  * The minimum is a ratchet, set just under what is measured today. It exists to stop
  * coverage sliding, not to be hit exactly — raise it when the real number moves up.
  */
lazy val coverageSettings = Seq(
  coverageMinimumStmtTotal   := 87,
  coverageMinimumBranchTotal := 83,
  coverageFailOnMinimum      := true,
  // A `main` that parses argv and writes files. Its body is covered by running it, not by
  // a unit test, and mocking a filesystem to reach 100% would be testing the mock.
  coverageExcludedPackages   := "thicket\\.tools\\.shim\\.Main.*",
  // On sbt 2 nothing recreates the scoverage-data directory after a `clean`, and two
  // separate things need it to exist:
  //
  //   - the *compiler* writes `scoverage.coverage` there, the metadata that maps a
  //     measurement back to a statement. If the directory is missing it skips the file
  //     silently, and the module then vanishes from the aggregate report with no error —
  //     which is how `signals` disappeared while still producing measurements.
  //   - instrumented code at *run* time writes one measurement file per thread, and
  //     without the directory throws FileNotFoundException inside a test, which reads like
  //     a test failure and is not one.
  //
  // `clean` must not share an sbt invocation with a coverage run: see docs/12 §12.9.
  coverageOutputHTML := true
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-source:3.9",
    // Braces, not significant indentation. `-no-indent` makes the compiler reject
    // indentation syntax outright rather than merely allowing braces.
    "-no-indent",
    "-Wunused:all",
    "-Werror"
  ),
  // ThreadGuard.install is process-global, so suites must not overlap.
  Test / parallelExecution := false
)

// zio-test. Property testing comes with it (`check` + `Gen`), so there is no scalacheck
// equivalent to add. sbt 2 has no `%%%`, hence the explicit artefact suffixes.
//
// zio-test-sbt for Native is built against Scala Native's test-interface 0.5.10 while this
// build is on 0.5.12. Scala Native keeps binary compatibility across 0.5.x patch releases,
// so this is an eviction *policy* disagreement rather than a real incompatibility, and the
// scheme says so narrowly instead of turning eviction errors off build-wide.
ThisBuild / libraryDependencySchemes +=
  "org.scala-native" % "test-interface_native0.5_3" % VersionScheme.EarlySemVer
lazy val zioTestFramework = testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

lazy val zioTestJvm    = Seq("dev.zio" %% "zio-test"     % zioV % Test,
                             "dev.zio" %% "zio-test-sbt" % zioV % Test)
lazy val zioTestJs     = Seq("dev.zio" % "zio-test_sjs1_3"     % zioV % Test,
                             "dev.zio" % "zio-test-sbt_sjs1_3" % zioV % Test)
lazy val zioTestNative = Seq("dev.zio" % "zio-test_native0.5_3"     % zioV % Test,
                             "dev.zio" % "zio-test-sbt_native0.5_3" % zioV % Test)

/** Fine-grained reactive core. No dependencies beyond the Scala library (S5). */
lazy val signals = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/signals"))
  .settings(commonSettings, zioTestFramework, coverageSettings, name := "thicket-signals")
  .jvmSettings(libraryDependencies ++= zioTestJvm)
  .jsSettings(libraryDependencies ++= zioTestJs)
  .nativeSettings(libraryDependencies ++= zioTestNative)

/** The framework/toolkit seam. Pure types: no platform code. */
lazy val rendererApi = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/renderer-api"))
  .settings(commonSettings, name := "thicket-renderer-api")
  // Instrumented code writes its measurements into its *own* module's scoverage-data
  // directory, and that directory is only created when that module's tests run. This
  // module is pure types with no tests of its own, so under `coverage` its default methods
  // — `Renderer.moveAfter` and friends, reached from core's tests — throw
  // FileNotFoundException mid-test. It is exercised through core either way; instrumenting
  // it only buys a number for three default method bodies.
  .settings(coverageEnabled := false)

/** Element tree, DSL and reconciler. */
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/core"))
  .dependsOn(signals % "compile->compile;test->test", rendererApi)
  .settings(commonSettings, zioTestFramework, coverageSettings, name := "thicket-core")
  .jvmSettings(libraryDependencies ++= zioTestJvm)
  .jsSettings(libraryDependencies ++= zioTestJs)
  .nativeSettings(libraryDependencies ++= zioTestNative)

/** GTK's C glue (and that of its transitive bindings, e.g. graphene) is compiled by Scala
  * Native in whichever project performs the link, so every downstream project needs these
  * flags too — `nativeConfig` is not inherited through `dependsOn`.
  */
lazy val gtkNativeSettings = Seq(
  nativeConfig ~= { c =>
    val cflags  = "pkg-config --cflags gtk4".!!.trim.split(" ").filter(_.nonEmpty).toSeq
    val ldflags = "pkg-config --libs gtk4".!!.trim.split(" ").filter(_.nonEmpty).toSeq
    c.withLTO(scala.scalanative.build.LTO.none)
      .withMode(scala.scalanative.build.Mode.debug)
      .withCompileOptions(c.compileOptions ++ cflags)
      .withLinkingOptions(c.linkingOptions ++ ldflags)
  }
)

/** GTK4 renderer (Linux). Scala Native only. */
lazy val rendererGtk = project
  .in(file("modules/renderer-gtk"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(core.native)
  .settings(commonSettings)
  .settings(
    name := "thicket-renderer-gtk",
    libraryDependencies ++= Seq(
      "com.indoorvivants.gnome" % "gtk4_native0.5_3" % "0.2.6",
      // GCancellable and GAsyncResult: GtkAlertDialog reports the chosen button through
      // an async callback, and cancelling is the only way to dismiss one programmatically.
      "com.indoorvivants.gnome" % "gio_native0.5_3"  % "0.2.6"
    )
  )
  .settings(gtkNativeSettings)

/** AppKit's Swift shim is a separate static library, because Scala Native's own clang
  * invocation knows nothing about Swift. `modules/renderer-apple/shim/build-shim.sh`
  * builds it; these flags link it and AppKit itself.
  *
  * Computed inside `nativeConfig ~=` rather than at build-load time, so nothing here runs
  * on a machine that is not a Mac — the same reason `gtkNativeSettings` shells out to
  * pkg-config lazily.
  */
lazy val appleNativeSettings = Seq(
  nativeConfig ~= { c =>
    val shim = (file("modules") / "renderer-apple" / "shim" / "build").getAbsolutePath
    c.withLTO(scala.scalanative.build.LTO.none)
      .withMode(scala.scalanative.build.Mode.debug)
      .withCompileOptions(c.compileOptions ++ Seq("-I" + (file("modules") / "renderer-apple" / "shim" / "include").getAbsolutePath))
      .withLinkingOptions(
        c.linkingOptions ++ Seq(
          "-L" + shim,
          "-lthicketapple",
          "-framework", "AppKit",
          "-framework", "Foundation",
          // Swift's own runtime, which the shim's objects need at link time.
          "-L/usr/lib/swift",
          "-Xlinker", "-rpath", "-Xlinker", "/usr/lib/swift"
        )
      )
  }
)

/** The iOS simulator variant. Three differences from the macOS one, each forced:
  *
  *   - `libraryStatic`, because the host owns `@main` (see `todoIos`);
  *   - `GC.immix`, the only GC that builds for iOS (S1);
  *   - `target.os -> "darwin"`. An iOS triple makes `target.os == "ios"`, javalib's
  *     `LinktimeInfo.isMac` accepts only "darwin"/"macosx", and `PosixThread` then calls
  *     `pthread_condattr_setclock`, which no Apple platform has (S1). Mandatory on every
  *     iOS target; without it the link fails.
  *
  * No linking options for the shim: a static archive is not linked, so the `sui_*` symbols
  * stay undefined until `ios-app/build-app.sh` resolves them against the UIKit shim.
  * `xcrun` runs inside `nativeConfig`, not at build-load time, so the Linux box is
  * unaffected.
  */
lazy val iosNativeSettings = Seq(
  nativeConfig := {
    val c      = nativeConfig.value
    val triple = "arm64-apple-ios17.0-simulator"
    val sdk    = scala.sys.process.Process(Seq("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path")).!!.trim
    val flags  = Seq("-target", triple, "-isysroot", sdk)
    c.withBuildTarget(scala.scalanative.build.BuildTarget.libraryStatic)
      .withGC(scala.scalanative.build.GC.immix)
      .withMode(scala.scalanative.build.Mode.debug)
      .withLTO(scala.scalanative.build.LTO.none)
      .withTargetTriple(triple)
      .withCompileOptions(c.compileOptions ++ flags)
      .withLinkingOptions(c.linkingOptions ++ flags)
      .withLinktimeProperties(
        c.linktimeProperties + ("scala.scalanative.meta.linktimeinfo.target.os" -> "darwin")
      )
  }
)

/** Both Apple example hosts compile the same `examples/todo-apple/shared` sources. */
lazy val appleSharedExampleSources = Seq(
  Compile / unmanagedSourceDirectories +=
    (ThisBuild / baseDirectory).value / "examples" / "todo-apple" / "shared" / "src" / "main" / "scala"
)

/** AppKit renderer (macOS). Scala Native only, and never aggregated: it links a Swift
  * static library and AppKit, so it can only build on a Mac.
  */
lazy val rendererApple = project
  .in(file("modules/renderer-apple"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(core.native)
  .settings(commonSettings)
  .settings(name := "thicket-renderer-apple")
  .settings(appleNativeSettings)

/** The ZIO bridge: effects at the edges of an otherwise effect-free core (docs/07 §7.13).
  *
  * Native needs `scala-java-time`, because `zio.Duration` *is* `java.time.Duration` and
  * Scala Native's javalib has no `java.time` — ZIO does not link at all without it (S8).
  */
lazy val effectZio = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/effect-zio"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings, zioTestFramework, coverageSettings, name := "thicket-effect-zio")
  .jvmSettings(
    libraryDependencies ++= zioTestJvm ++ Seq(
      "dev.zio" %% "zio"         % zioV,
      "dev.zio" %% "zio-streams" % zioV
    )
  )
  .nativeSettings(
    libraryDependencies ++= zioTestNative ++ Seq(
      "dev.zio"           % s"zio_native0.5_3"            % zioV,
      "dev.zio"           % s"zio-streams_native0.5_3"    % zioV,
      "io.github.cquiroz" % "scala-java-time_native0.5_3" % "2.7.0"
    )
  )

/** Android renderer. Plain JVM Scala: R8 dexes it and ART runs it (S2).
  *
  * `android.jar` arrives through sbt's unmanaged `lib/` convention because sbt 2 cannot
  * put a `File` on a classpath — run `modules/renderer-android/setup.sh` first.
  */
lazy val rendererAndroid = project
  .in(file("modules/renderer-android"))
  .dependsOn(core.jvm)
  .settings(commonSettings)
  .settings(
    name := "thicket-renderer-android",
    // Android's runtime is not the JVM's: target the bytecode ART accepts. Scala 3.9
    // cannot emit lower than 17 (S2), which AGP handles with desugaring.
    scalacOptions ++= Seq("-release", "17")
  )

/** The example UI, shared by every example app and free of platform references. */
lazy val examplesShared = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("examples/shared"))
  .dependsOn(core)
  .settings(commonSettings, name := "thicket-examples-shared", publish / skip := true)
  .jvmSettings(scalacOptions ++= Seq("-release", "17"))

lazy val counterGtk = project
  .in(file("examples/counter-gtk"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(rendererGtk, examplesShared.native)
  .settings(commonSettings)
  .settings(gtkNativeSettings)
  .settings(
    name := "counter-gtk",
    publish / skip := true,
    // Two demos in one project; pick with `counterGtk/runMain`.
    Compile / mainClass := Some("example.Todo")
  )

/** The macOS example. Not aggregated, for the same reason the renderer is not. */
lazy val todoMacos = project
  .in(file("examples/todo-apple/macos"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(rendererApple, examplesShared.native)
  .settings(commonSettings)
  .settings(appleNativeSettings)
  .settings(appleSharedExampleSources)
  .settings(
    name := "todo-macos",
    publish / skip := true,
    Compile / mainClass := Some("example.TodoMac")
  )

/** The iOS example: the same `TodoApp`, the same `rendererApple`, the same self-test —
  * only the entry point differs. That is the claim issue #3 makes, so it is worth the two
  * hosts sharing `examples/todo-apple/shared` literally rather than by copy.
  *
  * Links to a static archive rather than an executable: `@main` belongs to the Swift host
  * in `ios/ios-app/`, because iOS 27 requires UIScene adoption and a scene delegate cannot
  * live in the archive (S3). `ios-app/build-app.sh` does the final link and runs it.
  */
lazy val todoIos = project
  .in(file("examples/todo-apple/ios"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(rendererApple, examplesShared.native)
  .settings(commonSettings)
  .settings(iosNativeSettings)
  .settings(appleSharedExampleSources)
  .settings(
    name := "todo-ios",
    publish / skip := true
  )

/** The Android example's Scala half: compiled to a plain JAR that the Gradle project in
  * `examples/todo-android/app` consumes. Gradle's `scala` plugin cannot coexist with AGP
  * (it applies JavaPlugin, which collides on the `implementation` configuration), so this
  * hand-off is the only shape available — see S2.
  */
lazy val todoAndroid = project
  .in(file("examples/todo-android/scala"))
  .dependsOn(rendererAndroid, examplesShared.jvm)
  .settings(commonSettings)
  .settings(
    name := "todo-android",
    publish / skip := true,
    scalacOptions ++= Seq("-release", "17")
  )

/** The shim generator and, more importantly, the ABI consistency check.
  *
  * Plain JVM, depends on nothing in the framework, and deliberately aggregated: the check
  * it runs is the only thing that can catch a disagreement between the C header, the two
  * Swift shims and the Scala externs, and that disagreement is a silent runtime fault
  * rather than a compile error. It must run on every machine, including the ones with no
  * Xcode.
  */
lazy val shimGen = project
  .in(file("tools/shim-gen"))
  .settings(commonSettings, zioTestFramework, coverageSettings)
  .settings(
    name           := "thicket-shim-gen",
    publish / skip := true,
    libraryDependencies ++= zioTestJvm,
    // The suite reads the checked-in shim artefacts, so it needs the repository root
    // rather than the subproject's base directory.
    Test / javaOptions += s"-Dthicket.root=${(ThisBuild / baseDirectory).value}",
    Test / fork := true
  )

lazy val root = project
  .in(file("."))
  .aggregate(signals.jvm, signals.js, signals.native,
             rendererApi.jvm, rendererApi.js, rendererApi.native,
             core.jvm, core.js, core.native,
             effectZio.jvm, effectZio.native,
             shimGen)
  .settings(publish / skip := true, name := "thicket")
