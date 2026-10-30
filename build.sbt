// Thicket — see docs/. Toolchain policy: latest stable Scala and sbt (docs/decisions.md).
// sbt 2 has no `%%%`, so cross-platform test deps carry explicit artefact suffixes.
// sbt-git's autoImport is not in build.sbt scope here (hence the fully-qualified
// `com.github.sbt.git.GitVersioning` on each project), so its keys need this alias.
import com.github.sbt.git.SbtGit.GitKeys

lazy val SCALA = "3.9.0"

/** Building the Apple shim needs Xcode. */
lazy val isMac = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")

scalaVersion := SCALA
organization := "net.leibman"

/** Central Portal: `-SNAPSHOT` to the snapshot repository, a release version to `localStaging` for `sonaUpload`, and
  * nothing else anywhere.
  *
  * The "anything else" is the point. Between tags, `git describe` makes versions like `0.1.0-3-gdeadbee`: neither a
  * snapshot nor a release. Treating every non-snapshot as a release staged those for Central, where a release is
  * permanent. `None` makes `publish` fail with "Repository for publishing is not specified" instead; `publishLocal`
  * is unaffected. docs/14 has the procedure.
  */
lazy val releaseVersion = """\d+\.\d+\.\d+(-(M|RC)\d+)?""".r
publishTo := {
  val v = version.value
  if v.endsWith("-SNAPSHOT") then Some("central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/")
  else if releaseVersion.matches(v) then localStaging.value
  else None
}

pomIncludeRepository := { _ => false }

scmInfo := Some(
  ScmInfo(
    uri("https://github.com/rleibman/thicket"),
    "scm:git:git@github.com:rleibman/thicket.git"
  )
)

// NOTE: `version` is NOT set here. `GitVersioning` owns it, and an explicit
// `version := ...` is silently overridden — the hard-coded "0.1.0-SNAPSHOT" that used to be
// on this line never reached a single artefact.
//
// What comes out instead:
//   - on a tag `v0.2.0`              -> 0.2.0
//   - some commits after that tag    -> 0.2.0-3-gdeadbee   (not publishable: see publishTo)
//   - no tag reachable at all        -> 0.1.0-<sha>        (baseVersion + sha; a shallow clone)
// Build scope: sbt-git reads it there, and a bare (per-project) setting is silently unused.
ThisBuild / GitKeys.baseVersion := "0.1.0"
// GitVersioning also sets `ThisBuild / version`, which every project then overrides with its
// own; sbt's lint reports the build-level one as unused on every load.
Global / excludeLintKeys += version
// `useGitDescribe` is ALSO set per project, in `commonSettings`, and it has to be.
// GitVersioning sets `useGitDescribe := false` in its own *projectSettings*, which shadows
// a build-level setting entirely: `ThisBuild / useGitDescribe` once read `true` while
// `coreJVM / useGitDescribe` read `false`, so every project ignored the tag and fell back to
// baseVersion + sha. `v0.1.0` was tagged and the artefacts still came out as
// `0.1.0-<sha>` — the tag was inert, which is the sort of thing a release notices far too
// late.
GitKeys.useGitDescribe := true
// Tags are `v`-prefixed; anything else is not a version and is left for `git describe` to
// render as-is rather than being mangled into one.
GitKeys.gitTagToVersionNumber := { tag =>
  if tag.startsWith("v") && tag.length > 1 then Some(tag.drop(1)) else None
}
// `uri`, not `url`: sbt deprecated `url` in 2.0.2.
licenses     := Seq("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0"))

// sbt-header cannot auto-detect the licence from `licenses` alone — it needs the owner and
// the year too, and errors out with "Unable to auto detect project license" without them.
// Set explicitly rather than left to detection, and matching LICENSE, which says
// "Copyright 2026 Roberto Leibman".
organizationName := "Roberto Leibman"
startYear        := Some(2026)
headerLicense    := Some(HeaderLicense.ALv2("2026", "Roberto Leibman"))

// POM metadata, with `scmInfo` above and `licences`/`organizationName`.
homepage := Some(uri("https://github.com/rleibman/thicket"))
developers := List(
  Developer(
    id    = "rleibman",
    name  = "Roberto Leibman",
    email = "roberto@leibman.net",
    url   = uri("https://github.com/rleibman")
  )
)
// Declared so coursier and sbt can tell an eviction from a breaking change rather than
// guessing from the number. `early-semver` is the right one for a 0.x library that intends
// to keep binary compatibility within a minor line once it reaches 1.0 — see docs/14.
versionScheme := Some("early-semver")

val zioV = "2.1.26"

/** Coverage settings, applied to every module that has JVM tests.
  *
  * **The number only covers the effect-free core**, and that is worth stating plainly rather than quoting an aggregate
  * that sounds worse than it is. The GTK, Android and Apple renderers are Scala Native or ART code exercised by
  * self-tests that drive the real toolkit in a real process; scoverage instruments neither, so those modules contribute
  * nothing here and their absence is not a gap in testing. `docs/12` §12.9 says which is which.
  *
  * The minimums are in [[coverageGate]], on the root project, because that is where `coverageAggregate` reads them.
  */
lazy val coverageSettings = Seq(
  // A `main` that parses argv and writes files. Its body is covered by running it, not by
  // a unit test, and mocking a filesystem to reach 100% would be testing the mock.
  coverageExcludedPackages := "thicket\\.tools\\.shim\\.Main.*",
  coverageOutputHTML       := true,
  // `bin/coverage.sh` passes a fresh `-Dthicket.coverageRun` each run, and it goes into the
  // compile's options so that no sbt 2 disk-cache entry can match: every coverage run really
  // compiles. A cache hit restores the instrumented classes but not `scoverage.coverage`, the
  // metadata that maps a measurement to a statement — the compiler writes that as a side
  // effect — so a module whose metadata is gone drops out of the report with no error (#80).
  // The option is inert: nothing reads the macro setting.
  Compile / compile / scalacOptions ++= Def.uncached(
    sys.props
      .get("thicket.coverageRun")
      .filter(_ => coverageEnabled.value)
      .map(run => s"-Xmacro-settings:thicket.coverageRun=$run")
      .toSeq
  )
)

/** The coverage ratchet: set just under what is measured today, to stop coverage sliding, not to be hit exactly. Raise it
  * when the real number moves up.
  *
  * It lives on the root project because `coverageAggregate` checks the aggregating project's own settings. Until #80 it
  * sat on the modules, where nothing reads it, and the root kept the plugin's `coverageFailOnMinimum := false` — so the
  * aggregate passed at 0.5%.
  *
  * The per-package floor is what catches a module losing its measurements. A Scala 3 coverage compile deletes the
  * module's `scoverage.measurements.*` files, and the runtime records each statement once per JVM, so anything that
  * recompiles a module after its tests have run in the same sbt invocation — a `BuildInfo` timestamp did, before #81 —
  * leaves that module at ~0% while the total still looks plausible.
  */
lazy val coverageGate = Seq(
  coverageMinimumStmtTotal      := 93,
  coverageMinimumBranchTotal    := 87,
  coverageMinimumStmtPerPackage := 85,
  coverageFailOnMinimum         := true
)

lazy val commonSettings = Seq(
  // Project scope on purpose — see the note at `ThisBuild / GitKeys.useGitDescribe`.
  GitKeys.useGitDescribe := true,
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
libraryDependencySchemes +=
  "org.scala-native" % "test-interface_native0.5_3" % VersionScheme.EarlySemVer
lazy val zioTestFramework = testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

lazy val zioTestJvm = Seq("dev.zio" %% "zio-test" % zioV % Test, "dev.zio" %% "zio-test-sbt" % zioV % Test)
lazy val zioTestJs = Seq("dev.zio" % "zio-test_sjs1_3" % zioV % Test, "dev.zio" % "zio-test-sbt_sjs1_3" % zioV % Test)
lazy val zioTestNative =
  Seq("dev.zio" % "zio-test_native0.5_3" % zioV % Test, "dev.zio" % "zio-test-sbt_native0.5_3" % zioV % Test)

/** Per-module BuildInfo.
  *
  * Every module had `BuildInfoPlugin` enabled and none set `buildInfoPackage`, so all
  * fourteen generated the *same* `buildinfo.BuildInfo` class. Harmless while nothing reads
  * it, and a genuine problem the moment two of them meet on one classpath — which `core`
  * and `renderer-api` already do in every app.
  *
  * The package is each module's own, suffixed `.buildinfo`, so the object can never collide
  * with hand-written code. For a `crossProject` the package is deliberately the *same* on
  * every platform: shared source has to be able to name it.
  *
  * `buildTime` adds `BuildInfoOption.BuildTime` rather than a `buildTime` setting key — a setting is evaluated once per
  * sbt session, so it would record when the shell was started, not when the artefact was built. **Off by default**:
  * a timestamp makes every build of a published jar differ from the last, so the same commit never produces the same
  * artefact (review 2026-10-20, P2-31). The gallery, which shows it, opts in. Turning it on for a tested module also
  * breaks coverage: the regenerated source recompiles the module between its tests and the report (#80).
  */
def thicketBuildInfo(
  pkg:       String,
  buildTime: Boolean = false
): Seq[Setting[?]] = Seq(
  buildInfoPackage := pkg,
  buildInfoKeys := Seq[BuildInfoKey](
    // sbt-thicket's dependencies are built from this, so the plugin cannot name a group the framework was not published
    // under — which is how #70's move to `net.leibman` left it asking for `dev.thicket`.
    organization,
    name,
    version,
    scalaVersion,
    sbtVersion,
    // Git provenance, which is the point of BuildInfo in a framework: a bug report that
    // quotes a version is useful, one that quotes a commit and whether the tree was dirty
    // is actionable.
    GitKeys.gitCurrentBranch,
    GitKeys.gitHeadCommit,
    GitKeys.gitUncommittedChanges
  ),
  buildInfoOptions ++= (if buildTime then Seq(BuildInfoOption.BuildTime) else Nil)
)

/** Fine-grained reactive core. No dependencies beyond the Scala library (S5). */
lazy val signals = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/signals"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("thicket.signals.buildinfo"))
  .settings(commonSettings, zioTestFramework, coverageSettings, name := "thicket-signals")
  .jvmSettings(libraryDependencies ++= zioTestJvm)
  .jsSettings(libraryDependencies ++= zioTestJs)
  .nativeSettings(libraryDependencies ++= zioTestNative)

/** The framework/toolkit seam. Pure types: no platform code. */
lazy val rendererApi = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/renderer-api"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("thicket.renderer.buildinfo"))
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
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("thicket.core.buildinfo"))
  .dependsOn(signals % "compile->compile;test->test", rendererApi)
  .settings(commonSettings, zioTestFramework, coverageSettings, name := "thicket-core")
  .jvmSettings(libraryDependencies ++= zioTestJvm)
  .jsSettings(libraryDependencies ++= zioTestJs)
  .nativeSettings(libraryDependencies ++= zioTestNative)

/** GTK's link settings, from the same definition the published `sbt-thicket` plugin uses.
  *
  * `ThicketNativeFlags` lives in `tools/sbt-thicket/shared/src/main/scala` and is compiled into this meta-build by
  * `project/build.sbt` as well as into the plugin. There is one copy of these flags, not two, which is the point of
  * #40: a plugin whose flags can disagree with the renderer's own build reproduces the failure it was meant to prevent.
  *
  * `GC.immix` is new here and was not in the copy this replaced. It is Scala Native's default *unless* `SCALANATIVE_GC`
  * says otherwise, so it was a default the environment could change; the template already pinned it.
  */
lazy val gtkNativeSettings = Seq(
  nativeConfig ~= thicket.sbt.ThicketNativeFlags.gtk
)

/** GTK4 renderer (Linux). Scala Native only. */
lazy val rendererGtk = project
  .in(file("modules/renderer-gtk"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("thicket.renderer.gtk.buildinfo"))
  .dependsOn(core.native)
  .settings(commonSettings)
  .settings(
    name := "thicket-renderer-gtk",
    libraryDependencies ++= Seq(
      "com.indoorvivants.gnome" % "gtk4_native0.5_3" % "0.2.6",
      // GCancellable and GAsyncResult: GtkAlertDialog reports the chosen button through
      // an async callback, and cancelling is the only way to dismiss one programmatically.
      "com.indoorvivants.gnome" % "gio_native0.5_3" % "0.2.6",
      // AdwNavigationView: GTK core has no navigation container. GtkStack gives transitions
      // but no back-gesture semantics, and GNOME apps use libadwaita for this.
      "com.indoorvivants.gnome" % "adwaita_native0.5_3" % "0.2.6"
    )
  )
  .settings(gtkNativeSettings)

/** AppKit's Swift shim is a separate static library, because Scala Native's own clang invocation knows nothing about
  * Swift. `modules/renderer-apple/shim/build-shim.sh` builds it; these flags link it and AppKit itself.
  *
  * The flags are `ThicketNativeFlags.apple`, shared with the plugin like the GTK ones. The shim directories stay here
  * because they are this repository's layout, and an app outside it has no `modules/` — which is why there is no Apple
  * plugin yet (#41, and `tools/sbt-thicket/README.md`).
  */
lazy val appleNativeSettings = Seq(
  nativeConfig ~= thicket.sbt.ThicketNativeFlags.apple(
    shimLibDir = file("modules") / "renderer-apple" / "shim" / "build",
    shimIncludeDir = file("modules") / "renderer-apple" / "shim" / "include"
  )
)

/** The iOS simulator variant: `libraryStatic`, `GC.immix`, a target triple, an SDK path asked of the Xcode command-line
  * tools, and the `target.os -> "darwin"` linktime property without which the link fails. All of it, and why, in
  * `ThicketNativeFlags.ios`.
  */
lazy val iosNativeSettings = Seq(
  nativeConfig ~= thicket.sbt.ThicketNativeFlags.ios()
)

/** Both Apple example hosts compile the same `examples/todo-apple/shared` sources. */
lazy val appleSharedExampleSources = Seq(
  Compile / unmanagedSourceDirectories +=
    (ThisBuild / baseDirectory).value / "examples" / "todo-apple" / "shared" / "src" / "main" / "scala"
)

/** SQLite on macOS and iOS is the system's `libsqlite3`, not the amalgamation `scala-native-jdbc-sqlite` bundles: on the
  * iOS gallery in `releaseFast` + `LTO.full` the amalgamation costs 1.09 MiB stripped against N-03's 6 MB, the system
  * library nothing (`docs/decisions.md`, 2026-10-22). The macOS link takes `-lsqlite3` here; an iOS app is a static
  * archive, so its host's final link (`build-app.sh`) passes it.
  */
lazy val appleSystemSqlite = Seq(
  excludeDependencies += ExclusionRule("com.github.lolgab", "scala-native-sqlite-amalgamation"),
  nativeConfig ~= (c => if c.buildTarget == scala.scalanative.build.BuildTarget.libraryStatic then c else c.withLinkingOptions(c.linkingOptions :+ "-lsqlite3"))
)

/** The gallery's Apple-only sources, shared by the macOS and iOS hosts.
  *
  * The self-test lives here rather than in `galleryShared` because it imports `AppleInspect` — and `galleryShared` must
  * stay free of renderer types, since it also compiles for the JVM (Android).
  */
lazy val galleryAppleSharedSources = Seq(
  Compile / unmanagedSourceDirectories +=
    (ThisBuild / baseDirectory).value / "examples" / "gallery" / "apple-shared" / "src" / "main" / "scala"
)

/** AppKit renderer (macOS). Scala Native only, and never aggregated: it links a Swift static library and AppKit, so it
  * can only build on a Mac.
  */
lazy val rendererApple = project
  .in(file("modules/renderer-apple"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("thicket.renderer.apple.buildinfo"))
  .dependsOn(core.native)
  .settings(commonSettings)
  .settings(name := "thicket-renderer-apple")
  // `Shim.scala` is generated by `shimGen` and checked in, and `ConsistencySpec` compares it
  // **byte-for-byte** against what the generator emits — the only thing standing between a
  // C/Swift/Scala ABI disagreement and a garbage register read in a callback on a phone. The
  // generator owns those bytes, so sbt-header must not add a licence to them: enabling the
  // plugin broke that test immediately. The fix belongs here rather than in the generator,
  // which would otherwise have to know the licence text to keep the bytes matching.
  .settings(headerSources / excludeFilter := HiddenFileFilter || "Shim.scala")
  .settings(appleNativeSettings)
  // The Swift shim travels in the published jar (#41). Without it an app outside this repository has the Scala half
  // of the renderer and nothing to link its `sui_*` symbols against, because `libthicketapple.a` is built by a shell
  // script here and was never published. Built at *package* time, not on every compile: it needs Xcode, and it is
  // only wanted when a jar is being made. `sbt-thicket`'s Apple plugins unpack it from the resolved jar.
  //
  // Off a Mac there is no Xcode, so the shim is not built and the jar holds only the Scala half. That jar is fine to
  // *compile against* — sbt 2 puts jars, not class directories, on dependants' classpaths, so before this guard the
  // Apple example apps could not even be type-checked on Linux — and wrong to *publish*, so publishing it is skipped
  // anywhere but a Mac.
  .settings(
    publish / skip := !isMac,
    Compile / packageBin / mappings ++= {
      val shim = (ThisBuild / baseDirectory).value / "modules" / "renderer-apple" / "shim"
      val conv = fileConverter.value
      if !isMac then Seq.empty
      else {
        for target <- Seq("macos", "ios-sim") do {
          val code = scala.sys.process.Process(Seq("bash", (shim / "build-shim.sh").getAbsolutePath, target), shim).!
          if code != 0 then sys.error(s"build-shim.sh $target failed with exit $code")
        }
        Seq(
          shim / "build" / "libthicketapple.a"       -> "thicket-apple/macos/libthicketapple.a",
          shim / "build-ios" / "libthicketapple.a"   -> "thicket-apple/ios-sim/libthicketapple.a",
          shim / "include" / "thicket_apple.h"       -> "thicket-apple/include/thicket_apple.h",
          shim / "include" / "thicket_apple_types.h" -> "thicket-apple/include/thicket_apple_types.h"
        ).map((f, path) => conv.toVirtualFile(f.toPath) -> path)
      }
    }
  )

/** sttp client4 and caliban-client: the HTTP and GraphQL client stack of docs/16 §16.3. Versions resolved 2026-10-22
  * with `cs complete-dep com.softwaremill.sttp.client4:core_native0.5_3:` and
  * `cs complete-dep com.github.ghostdogpr:caliban-client_native0.5_3:`.
  */
val sttpV = "4.1.0"
val calibanV = "3.2.0"

/** HTTP for GTK: an sttp `SyncBackend` over libcurl, in place of sttp's own `CurlBackend` (docs/16 §16.4). libcurl is
  * loaded with `dlopen`, so neither building nor starting an app needs its `-dev` package; `thicket_curl.c` (in the
  * jar's `scala-native/`) makes the two variadic calls.
  */
lazy val httpGtk = project
  .in(file("modules/http-gtk"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, ScalaNativePlugin)
  .settings(
    commonSettings,
    zioTestFramework,
    name := "thicket-http-gtk",
    libraryDependencies ++= zioTestNative :+ ("com.softwaremill.sttp.client4" % "core_native0.5_3" % sttpV)
  )

/** HTTP for Android: sttp's `HttpURLConnectionBackend`, with repeated headers and cancellation by interrupt (docs/16
  * §16.4). Plain JVM — it uses no Android API — so its tests run on the desktop JDK, in `root`.
  */
lazy val httpAndroid = project
  .in(file("modules/http-android"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .settings(
    commonSettings,
    zioTestFramework,
    name := "thicket-http-android",
    scalacOptions ++= Seq("-release", "17"),
    libraryDependencies ++= zioTestJvm :+ ("com.softwaremill.sttp.client4" % "core_3" % sttpV)
  )

/** HTTP for Apple: an sttp `SyncBackend` over `URLSession` (#90, docs/16 §16.4). iOS has no libcurl, so sttp's own
  * native backend cannot link there.
  *
  * Its own module, never part of `rendererApple`: an app that does not depend on it links none of it — not the Scala,
  * and not `libthickethttp.a`. Never aggregated, like the renderer: building the Swift library needs Xcode.
  */
lazy val httpApple = project
  .in(file("modules/http-apple"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("thicket.http.apple.buildinfo"))
  .settings(commonSettings)
  .settings(
    name := "thicket-http-apple",
    libraryDependencies += "com.softwaremill.sttp.client4" % "core_native0.5_3" % sttpV
  )
  // Generated by shimGen and compared byte for byte, like the renderer's `Shim.scala`.
  .settings(headerSources / excludeFilter := HiddenFileFilter || "HttpShim.scala")
  // The Swift library travels in the jar, as the renderer's does (#41): `thicket-http-apple/` holds both slices and the
  // header, built at package time on a Mac. `sbt-thicket`'s Apple plugins unpack and link it when this jar is on an
  // app's classpath.
  .settings(
    publish / skip := !isMac,
    Compile / packageBin / mappings ++= {
      val shim = (ThisBuild / baseDirectory).value / "modules" / "http-apple" / "shim"
      val conv = fileConverter.value
      if !isMac then Seq.empty
      else {
        for target <- Seq("macos", "ios-sim") do {
          val code = scala.sys.process.Process(Seq("bash", (shim / "build-shim.sh").getAbsolutePath, target), shim).!
          if code != 0 then sys.error(s"http-apple build-shim.sh $target failed with exit $code")
        }
        Seq(
          shim / "build" / "libthickethttp.a"     -> "thicket-http-apple/macos/libthickethttp.a",
          shim / "build-ios" / "libthickethttp.a" -> "thicket-http-apple/ios-sim/libthickethttp.a",
          shim / "include" / "thicket_http.h"     -> "thicket-http-apple/include/thicket_http.h"
        ).map((f, path) => conv.toVirtualFile(f.toPath) -> path)
      }
    }
  )

/** The ZIO bridge: effects at the edges of an otherwise effect-free core (docs/07 §7.13).
  *
  * Native needs `scala-java-time`, because `zio.Duration` *is* `java.time.Duration` and Scala Native's javalib has no
  * `java.time` — ZIO does not link at all without it (S8).
  */
lazy val effectZio = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/effect-zio"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("thicket.effect.zio.buildinfo"))
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

/** Device services (`docs/16-device-services.md`): one API module per service, one implementation module per service
  * and platform, so an app takes only what it uses (owner's decision, 2026-10-21).
  */

/** Where an app keeps its files, and its preferences: the API, the file-backed preferences, and test fakes. */
lazy val storage = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/storage"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .settings(commonSettings, zioTestFramework, name := "thicket-storage")
  .jvmSettings(libraryDependencies ++= zioTestJvm, scalacOptions ++= Seq("-release", "17"))
  .nativeSettings(libraryDependencies ++= zioTestNative)

/** Storage on GTK: the XDG base directories. Plain Scala Native — it needs nothing from GTK itself, so it links no
  * GTK and its tests run in `testFull`.
  */
lazy val storageGtk = project
  .in(file("modules/storage-gtk"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, ScalaNativePlugin)
  .dependsOn(storage.native)
  .settings(commonSettings, zioTestFramework, name := "thicket-storage-gtk", libraryDependencies ++= zioTestNative)

/** Storage on macOS and iOS: Application Support, Caches and the per-user temporary directory, each under the bundle
  * id, and preferences in a file. Plain Scala Native, no Swift: it links nothing from the renderer, so an Apple app that
  * stores nothing does not carry it, and its tests run in `testFull` on any machine.
  */
lazy val storageApple = project
  .in(file("modules/storage-apple"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, ScalaNativePlugin)
  .dependsOn(storage.native)
  .settings(commonSettings, zioTestFramework, name := "thicket-storage-apple", libraryDependencies ++= zioTestNative)

/** Storage on Android: the app's internal directories and `SharedPreferences`. `android.jar` comes through the
  * renderer (`modules/renderer-android/setup.sh`).
  */
lazy val storageAndroid = project
  .in(file("modules/storage-android"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .dependsOn(storage.jvm, rendererAndroid)
  .settings(commonSettings, name := "thicket-storage-android", scalacOptions ++= Seq("-release", "17"))

/** SQLite through plain JDBC: `Sqlite.open`, the Scala Native driver, and the conformance checks every driver is held
  * to. The JVM side has no driver of its own (Android's is `sqliteAndroid`); its tests use xerial's.
  */
lazy val sqlite = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/sqlite"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .dependsOn(storage)
  .settings(commonSettings, zioTestFramework, name := "thicket-sqlite")
  .jvmSettings(
    scalacOptions ++= Seq("-release", "17"),
    libraryDependencies ++= zioTestJvm :+ ("org.xerial" % "sqlite-jdbc" % "3.53.4.0" % Test)
  )
  .nativeSettings(
    libraryDependencies ++= zioTestNative :+ ("com.github.lolgab" % "scala-native-jdbc-sqlite_native0.5_3" % "0.0.7")
  )

/** SQLite on Android: a JDBC driver over `android.database.sqlite`, since none is maintained (`docs/16` §16.5). */
lazy val sqliteAndroid = project
  .in(file("modules/sqlite-android"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .dependsOn(sqlite.jvm, rendererAndroid)
  .settings(commonSettings, name := "thicket-sqlite-android", scalacOptions ++= Seq("-release", "17"))

/** Secrets: small strings kept by the platform's secure store (`docs/16` §16.3). The API, a memory implementation and
  * the conformance checks every store is held to.
  */
lazy val secrets = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/secrets"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .settings(commonSettings, zioTestFramework, name := "thicket-secrets")
  .jvmSettings(libraryDependencies ++= zioTestJvm, scalacOptions ++= Seq("-release", "17"))
  .nativeSettings(libraryDependencies ++= zioTestNative)

/** Secrets on GTK: the Secret Service through libsecret, loaded with `dlopen` so that it is optional at run time and
  * needs no `-dev` package to build. Links no GTK, so its tests run in `testFull`.
  */
lazy val secretsGtk = project
  .in(file("modules/secrets-gtk"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, ScalaNativePlugin)
  .dependsOn(secrets.native)
  .settings(commonSettings, zioTestFramework, name := "thicket-secrets-gtk", libraryDependencies ++= zioTestNative)

/** Secrets on macOS and iOS: the Keychain (#108). Plain Scala Native over a small C helper that Scala Native's own clang
  * compiles (`resources/scala-native/thicket_keychain.c`); the helper names the Security and CoreFoundation frameworks
  * in its object file, so an app links them with no flags. No Swift, and no dependency on `rendererApple`.
  *
  * In `platforms`, not `root`: its tests ask the real Keychain, so they run on a Mac (`secretsApple/testFull`), and
  * Linux only compiles it (`platforms/Test/compile`).
  */
lazy val secretsApple = project
  .in(file("modules/secrets-apple"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, ScalaNativePlugin)
  .dependsOn(secrets.native)
  .settings(commonSettings, zioTestFramework, name := "thicket-secrets-apple", libraryDependencies ++= zioTestNative)

/** Secrets on Android: each value encrypted with an AES key that never leaves the Android Keystore. */
lazy val secretsAndroid = project
  .in(file("modules/secrets-android"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .dependsOn(secrets.jvm, rendererAndroid)
  .settings(commonSettings, name := "thicket-secrets-android", scalacOptions ++= Seq("-release", "17"))

/** The app's lifecycle and permissions (`docs/16` §16.3, §16.10 item 3): the API every later device service sits on,
  * and fakes for tests. Depends on `signals` alone: the lifecycle is a `Signal`.
  */
lazy val device = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/device"))
  .dependsOn(signals)
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .settings(commonSettings, zioTestFramework, name := "thicket-device")
  .jvmSettings(libraryDependencies ++= zioTestJvm, scalacOptions ++= Seq("-release", "17"))
  .nativeSettings(libraryDependencies ++= zioTestNative)

/** Lifecycle and permissions on GTK, from `GtkApp`'s window and application. In `platforms`, with the renderer. */
lazy val deviceGtk = project
  .in(file("modules/device-gtk"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, ScalaNativePlugin)
  .dependsOn(device.native, rendererGtk)
  .settings(commonSettings, name := "thicket-device-gtk")

/** Lifecycle and permissions on Android, from `ThicketActivity`'s callbacks and the system's permission dialog. */
lazy val deviceAndroid = project
  .in(file("modules/device-android"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .dependsOn(device.jvm, rendererAndroid)
  .settings(commonSettings, name := "thicket-device-android", scalacOptions ++= Seq("-release", "17"))

/** Android renderer. Plain JVM Scala: R8 dexes it and ART runs it (S2).
  *
  * `android.jar` arrives through sbt's unmanaged `lib/` convention because sbt 2 cannot put a `File` on a classpath —
  * run `modules/renderer-android/setup.sh` first.
  */
lazy val rendererAndroid = project
  .in(file("modules/renderer-android"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("thicket.renderer.android.buildinfo"))
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
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("example.todo.buildinfo"))
  .dependsOn(core)
  .settings(commonSettings, name := "thicket-examples-shared", publish / skip := true)
  .jvmSettings(scalacOptions ++= Seq("-release", "17"))

lazy val counterGtk = project
  .in(file("examples/counter-gtk"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.todo.gtk.buildinfo"))
  .dependsOn(rendererGtk, examplesShared.native)
  .settings(commonSettings)
  .settings(gtkNativeSettings)
  .settings(
    name           := "counter-gtk",
    publish / skip := true,
    // Two demos in one project; pick with `counterGtk/runMain`.
    Compile / mainClass := Some("example.Todo")
  )

/** The macOS example. Not aggregated, for the same reason the renderer is not. */
lazy val todoMacos = project
  .in(file("examples/todo-apple/macos"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.todo.macos.buildinfo"))
  .dependsOn(rendererApple, examplesShared.native)
  .settings(commonSettings)
  .settings(appleNativeSettings)
  .settings(appleSharedExampleSources)
  .settings(
    name                := "todo-macos",
    publish / skip      := true,
    Compile / mainClass := Some("example.TodoMac")
  )

/** The iOS example: the same `TodoApp`, the same `rendererApple`, the same self-test — only the entry point differs.
  * That is the claim issue #3 makes, so it is worth the two hosts sharing `examples/todo-apple/shared` literally rather
  * than by copy.
  *
  * Links to a static archive rather than an executable: `@main` belongs to the Swift host in `ios/ios-app/`, because
  * iOS 27 requires UIScene adoption and a scene delegate cannot live in the archive (S3). `ios-app/build-app.sh` does
  * the final link and runs it.
  */
lazy val todoIos = project
  .in(file("examples/todo-apple/ios"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.todo.ios.buildinfo"))
  .dependsOn(rendererApple, examplesShared.native)
  .settings(commonSettings)
  .settings(iosNativeSettings)
  .settings(appleSharedExampleSources)
  .settings(
    name           := "todo-ios",
    publish / skip := true
  )

/** The Android example's Scala half: compiled to a plain JAR that the Gradle project in `examples/todo-android/app`
  * consumes. Gradle's `scala` plugin cannot coexist with AGP (it applies JavaPlugin, which collides on the
  * `implementation` configuration), so this hand-off is the only shape available — see S2.
  */
lazy val todoAndroid = project
  .in(file("examples/todo-android/scala"))
  .dependsOn(rendererAndroid, examplesShared.jvm)
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("example.todo.android.buildinfo"))
  .settings(commonSettings)
  .settings(
    name           := "todo-android",
    publish / skip := true,
    scalacOptions ++= Seq("-release", "17")
  )


/** The component gallery: every widget the framework has, on one screen, built for every platform.
  *
  * Separate from the todo example on purpose. That one is an *app*, shaped by what an app needs; this one is a
  * conformance surface, shaped by the catalogue. Its job is to fail to build — or to look wrong — the moment a widget
  * is added without a renderer honouring it everywhere. See `examples/gallery/README.md` and
  * `docs/12-component-status.md` §12.10.
  */
lazy val galleryShared = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("examples/gallery/shared"))
  // effect-zio too: the gallery runs one real effect, so every host proves it installed a UI-thread poster.
  // storage and sqlite: the device services' section; each host supplies its platform's implementation.
  .dependsOn(core, effectZio, storage, sqlite, secrets, device)
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("example.gallery.buildinfo", buildTime = true))
  .settings(commonSettings, name := "thicket-gallery-shared", publish / skip := true)
  .jvmSettings(scalacOptions ++= Seq("-release", "17"))
  // Magnum, the typed layer over JDBC the docs point apps to (docs/16 §16.5), checked against the Native driver. Native
  // only: the official build does not run on Android (see MagnumChecks).
  .nativeSettings(libraryDependencies += "com.github.lolgab" % "magnum_native0.5_3" % "1.3.1")


lazy val galleryGtk = project
  .in(file("examples/gallery/gtk"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.gallery.gtk.buildinfo"))
  .dependsOn(rendererGtk, galleryShared.native, storageGtk, secretsGtk, deviceGtk)
  .settings(commonSettings)
  .settings(gtkNativeSettings)
  .settings(
    name                := "gallery-gtk",
    publish / skip      := true,
    Compile / mainClass := Some("example.gallery.GalleryGtk")
  )

/** The gallery for Android. JVM-side only, like `todoAndroid`: the APK is assembled by gradle under
  * `examples/gallery/android`.
  */
lazy val galleryAndroid = project
  .in(file("examples/gallery/android/scala"))
  // httpChecks: the gallery's self-test runs the HTTP checks over AndroidHttpBackend.
  .dependsOn(
    rendererAndroid,
    galleryShared.jvm,
    storageAndroid,
    sqliteAndroid,
    secretsAndroid,
    deviceAndroid,
    httpChecks.jvm,
    httpAndroid
  )
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("example.gallery.android.buildinfo"))
  .settings(commonSettings)
  .settings(
    name           := "gallery-android",
    publish / skip := true,
    scalacOptions ++= Seq("-release", "17")
  )


/** The macOS and iOS galleries. Not aggregated, for the same reason the Apple renderer is not: they need Xcode, so they
  * are built on a Mac.
  */
lazy val galleryMacos = project
  .in(file("examples/gallery/macos"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.gallery.macos.buildinfo"))
  .dependsOn(rendererApple, galleryShared.native, storageApple, secretsApple)
  .settings(commonSettings)
  .settings(appleNativeSettings)
  .settings(galleryAppleSharedSources)
  .settings(appleSystemSqlite)
  .settings(
    name                := "gallery-macos",
    publish / skip      := true,
    Compile / mainClass := Some("example.gallery.GalleryMac")
  )

lazy val galleryIos = project
  .in(file("examples/gallery/ios"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.gallery.ios.buildinfo"))
  .dependsOn(rendererApple, galleryShared.native, storageApple, secretsApple)
  .settings(commonSettings)
  .settings(iosNativeSettings)
  .settings(galleryAppleSharedSources)
  .settings(appleSystemSqlite)
  .settings(
    name           := "gallery-ios",
    publish / skip := true
  )

/** The HTTP self-test: the same sttp checks against a local server for every platform's backend — `URLSessionBackend`
  * on macOS and the iOS simulator (#90), `LibcurlBackend` on Linux, `AndroidHttpBackend` on Android (in the gallery's
  * self-test). `examples/http/selftest.sh` starts the server and runs the Apple and Linux hosts.
  */
lazy val httpChecks = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("examples/http/checks"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .settings(commonSettings, name := "http-checks", publish / skip := true)
  // caliban-client 3.2.0 was built against sttp 4.0.27; its JSON modules follow core to 4.1.0.
  .jvmSettings(
    scalacOptions ++= Seq("-release", "17"),
    libraryDependencies ++= Seq(
      "com.github.ghostdogpr"         % "caliban-client_3" % calibanV,
      "com.softwaremill.sttp.client4" % "core_3"           % sttpV,
      "com.softwaremill.sttp.client4" % "jsoniter_3"       % sttpV,
      "com.softwaremill.sttp.client4" % "json-common_3"    % sttpV
    )
  )
  .nativeSettings(
    libraryDependencies ++= Seq(
      "com.github.ghostdogpr"         % "caliban-client_native0.5_3" % calibanV,
      "com.softwaremill.sttp.client4" % "core_native0.5_3"           % sttpV,
      "com.softwaremill.sttp.client4" % "jsoniter_native0.5_3"       % sttpV,
      "com.softwaremill.sttp.client4" % "json-common_native0.5_3"    % sttpV
    )
  )

/** The Apple hosts' own checks (main-thread refusal, requests left in the Swift side), on top of [[httpChecks]]. */
lazy val httpExampleSettings = Seq(
  Compile / unmanagedSourceDirectories +=
    (ThisBuild / baseDirectory).value / "examples" / "http" / "apple" / "src" / "main" / "scala",
  publish / skip := true
)

/** Linux: `thicket-http-gtk`'s `LibcurlBackend`. Linking needs libidn2's development package (`libidn2-dev`), for
  * sttp-model's host names; libcurl itself is loaded at run time.
  */
lazy val httpLinux = project
  .in(file("examples/http/linux"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, ScalaNativePlugin)
  .dependsOn(httpChecks.native, httpGtk)
  .settings(commonSettings)
  .settings(
    name                := "http-linux",
    publish / skip      := true,
    Compile / mainClass := Some("example.http.HttpLinux")
  )

lazy val httpMacos = project
  .in(file("examples/http/macos"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.http.macos.buildinfo"))
  .dependsOn(httpApple, httpChecks.native, core.native)
  .settings(commonSettings, httpExampleSettings)
  .settings(
    name                := "http-macos",
    Compile / mainClass := Some("example.http.HttpMac"),
    nativeConfig ~= thicket.sbt.ThicketNativeFlags.appleHttp(file("modules") / "http-apple" / "shim" / "build")
  )

lazy val httpIos = project
  .in(file("examples/http/ios"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin, ScalaNativePlugin)
  .settings(thicketBuildInfo("example.http.ios.buildinfo"))
  .dependsOn(httpApple, httpChecks.native, core.native)
  .settings(commonSettings, httpExampleSettings)
  .settings(iosNativeSettings)
  .settings(name := "http-ios")

/** The shim generator and, more importantly, the ABI consistency check.
  *
  * Plain JVM, depends on nothing in the framework, and deliberately aggregated: the check it runs is the only thing
  * that can catch a disagreement between the C header, the two Swift shims and the Scala externs, and that disagreement
  * is a silent runtime fault rather than a compile error. It must run on every machine, including the ones with no
  * Xcode.
  */
lazy val shimGen = project
  .in(file("tools/shim-gen"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning)
  .settings(commonSettings, zioTestFramework, coverageSettings)
  .settings(
    name           := "thicket-shim-gen",
    publish / skip := true,
    libraryDependencies ++= zioTestJvm,
    // The suite reads the checked-in shim artefacts, so it needs the repository root
    // rather than the subproject's base directory.
    Test / javaOptions += s"-Dthicket.root=${(ThisBuild / baseDirectory).value}",
    Test / fork := true,
    // `shimGen/run` writes the generated header and Scala externs into the repository, so it
    // gets the same root; forked, so the property reaches it.
    Compile / run / javaOptions += s"-Dthicket.root=${(ThisBuild / baseDirectory).value}",
    Compile / run / fork := true
  )

/** `sbt-thicket`: the published sbt plugin that gives an app Thicket's Scala Native link settings, so it does not copy
  * them out of this file (#40).
  *
  * Its flag definitions are **not** in `src/main`. They are in `shared/src/main`, which this project compiles *and*
  * `project/build.sbt` adds to the meta-build — so `gtkNativeSettings` above and the plugin's `thicketGtkSettings` are
  * the same method on the same bytes. The alternative, this build depending on the published plugin, is circular: the
  * renderer could not be built until the plugin had been released.
  *
  * `scalaVersion` is pinned to the Scala **sbt itself** is built with, which `ThisBuild / scalaVersion := "3.9.0"`
  * would otherwise override. Being straight about the evidence: I expected 3.9.0 to break the consumer's meta-build,
  * because that is compiled by sbt's own Scala and a 3.8 compiler cannot read 3.9 TASTy. It did not — the template
  * loaded and linked against a plugin compiled with 3.9.0. So this is a precaution, for a consumer on an sbt older
  * than ours rather than a failure I reproduced, and it costs nothing: the plugin's sources compile under both.
  */
lazy val sbtThicket = project
  .in(file("tools/sbt-thicket"))
  .enablePlugins(AutomateHeaderPlugin, com.github.sbt.git.GitVersioning, BuildInfoPlugin)
  .settings(thicketBuildInfo("thicket.sbt.buildinfo"))
  .settings(commonSettings, zioTestFramework)
  .settings(
    name      := "sbt-thicket",
    sbtPlugin := true,
    // org.scala-sbt:main_3:2.0.9 depends on scala3-library_3 3.8.4. Raise this only together with sbt.
    scalaVersion := "3.8.4",
    // ...and 3.8.4 has no 3.9 source level to ask for.
    scalacOptions -= "-source:3.9",
    // One copy of the flags, compiled twice. See `project/build.sbt`.
    Compile / unmanagedSourceDirectories += baseDirectory.value / "shared" / "src" / "main" / "scala",
    // The plugin needs `nativeConfig`, so it depends on the plugin that defines it — which also means a consumer's
    // `project/plugins.sbt` no longer adds sbt-scala-native itself, and `enablePlugins(ThicketGtkPlugin)` enables
    // `ScalaNativePlugin` too.
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12"),
    libraryDependencies ++= zioTestJvm
  )

/** Everything that is not in `root`: the renderers and every example.
  *
  * Kept out of `root` because compiling it needs things a fresh clone does not have — `android.jar` (run
  * `modules/renderer-android/setup.sh`) for the Android projects. `sbt platforms/Test/compile` builds all of it on this
  * box, the Apple apps included: their Scala type-checks anywhere, and only *linking* them needs Xcode.
  *
  * It is not invisible to the root, though. `scalafmtAll`, `scalafmtCheckAll`, `headerCreateAll` and `headerCheckAll`
  * at the root reach into it (see `root` below), because none of those compile anything, and a formatter that skips
  * the renderers is how 26 files drifted before this existed.
  */
lazy val platforms = project
  .in(file("target/platforms-aggregate"))
  .enablePlugins(com.github.sbt.git.GitVersioning)
  .aggregate(
    rendererGtk,
    rendererAndroid,
    rendererApple,
    httpApple,
    httpMacos,
    httpIos,
    httpChecks.jvm,
    httpChecks.native,
    httpLinux,
    storageAndroid,
    sqliteAndroid,
    secretsAndroid,
    secretsApple,
    deviceGtk,
    deviceAndroid,
    examplesShared.jvm,
    examplesShared.native,
    counterGtk,
    todoAndroid,
    todoMacos,
    todoIos,
    galleryShared.jvm,
    galleryShared.native,
    galleryGtk,
    galleryAndroid,
    galleryMacos,
    galleryIos
  )
  .settings(publish / skip := true, name := "thicket-platforms")

/** The source-only tasks, run over `platforms` as well as `root`'s own aggregate. */
lazy val platformsFilter = ScopeFilter(inAggregates(platforms, includeRoot = false))
// sbt-header's tasks live per configuration, scalafmt's per project.
lazy val platformsConfigFilter =
  ScopeFilter(inAggregates(platforms, includeRoot = false), inConfigurations(Compile, Test))

lazy val root = project
  .in(file("."))
  .enablePlugins(com.github.sbt.git.GitVersioning)
  .aggregate(
    signals.jvm,
    signals.js,
    signals.native,
    rendererApi.jvm,
    rendererApi.js,
    rendererApi.native,
    core.jvm,
    core.js,
    core.native,
    effectZio.jvm,
    effectZio.native,
    storage.jvm,
    storage.native,
    storageGtk,
    storageApple,
    sqlite.jvm,
    sqlite.native,
    secrets.jvm,
    secrets.native,
    secretsGtk,
    device.jvm,
    device.native,
    httpGtk,
    httpAndroid,
    shimGen,
    sbtThicket
  )
  .settings(coverageGate)
  .settings(
    publish / skip := true,
    name           := "thicket",
    scalafmtAll := Def.uncached { val _ = scalafmtAll.all(platformsFilter).value },
    scalafmtCheckAll := Def.uncached { val _ = scalafmtCheckAll.all(platformsFilter).value },
    Compile / headerCreateAll := Def.uncached(headerCreateAll.all(platformsConfigFilter).value.flatten),
    Compile / headerCheckAll := Def.uncached(headerCheckAll.all(platformsConfigFilter).value.flatten)
  )

// What CI runs on Linux, and what to run before pushing. `testFull`, not `test`: sbt 2's `test` is incremental and
// runs nothing when nothing changed, which reads as a pass.
addCommandAlias("checkLinux", ";testFull;scalafmtCheckAll;headerCheckAll;platforms/Test/compile")
