import scala.sys.process.*

// scala-ui — see docs/. Toolchain policy: latest stable Scala and sbt (docs/decisions.md).
// sbt 2 has no `%%%`, so cross-platform test deps carry explicit artefact suffixes.

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / licenses     := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

val munitV      = "1.3.6"
val munitCheckV = "1.3.1"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-source:3.9",
    "-Wunused:all",
    "-Werror"
  ),
  // ThreadGuard.install is process-global, so suites must not overlap.
  Test / parallelExecution := false
)

lazy val munitJvm    = Seq("org.scalameta" %% "munit" % munitV % Test,
                           "org.scalameta" %% "munit-scalacheck" % munitCheckV % Test)
lazy val munitJs     = Seq("org.scalameta" % "munit_sjs1_3" % munitV % Test,
                           "org.scalameta" % "munit-scalacheck_sjs1_3" % munitCheckV % Test)
lazy val munitNative = Seq("org.scalameta" % "munit_native0.5_3" % munitV % Test,
                           "org.scalameta" % "munit-scalacheck_native0.5_3" % munitCheckV % Test)

/** Fine-grained reactive core. No dependencies beyond the Scala library (S5). */
lazy val signals = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/signals"))
  .settings(commonSettings, name := "scala-ui-signals")
  .jvmSettings(libraryDependencies ++= munitJvm)
  .jsSettings(libraryDependencies ++= munitJs)
  .nativeSettings(libraryDependencies ++= munitNative)

/** The framework/toolkit seam. Pure types: no platform code. */
lazy val rendererApi = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/renderer-api"))
  .settings(commonSettings, name := "scala-ui-renderer-api")
  .jvmSettings(libraryDependencies ++= munitJvm)
  .jsSettings(libraryDependencies ++= munitJs)
  .nativeSettings(libraryDependencies ++= munitNative)

/** Element tree, DSL and reconciler. */
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("modules/core"))
  .dependsOn(signals, rendererApi)
  .settings(commonSettings, name := "scala-ui-core")
  .jvmSettings(libraryDependencies ++= munitJvm)
  .jsSettings(libraryDependencies ++= munitJs)
  .nativeSettings(libraryDependencies ++= munitNative)

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
    name := "scala-ui-renderer-gtk",
    libraryDependencies += "com.indoorvivants.gnome" % "gtk4_native0.5_3" % "0.2.6"
  )
  .settings(gtkNativeSettings)

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
    name := "scala-ui-renderer-android",
    // Android's runtime is not the JVM's: target the bytecode ART accepts. Scala 3.9
    // cannot emit lower than 17 (S2), which AGP handles with desugaring.
    scalacOptions ++= Seq("-release", "17")
  )

/** The example UI, shared by every example app and free of platform references. */
lazy val examplesShared = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("examples/shared"))
  .dependsOn(core)
  .settings(commonSettings, name := "scala-ui-examples-shared", publish / skip := true)
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

lazy val root = project
  .in(file("."))
  .aggregate(signals.jvm, signals.js, signals.native,
             rendererApi.jvm, rendererApi.js, rendererApi.native,
             core.jvm, core.js, core.native)
  .settings(publish / skip := true, name := "scala-ui")
