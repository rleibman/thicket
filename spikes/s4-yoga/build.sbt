import bindgen.interface.Binding

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S4"

val yogaVersion = "3.2.1" // pinned; see third-party/README.md
val yogaRoot    = settingKey[File]("Vendored Yoga checkout")
val yogaBuild   = settingKey[File]("CMake build dir containing libyogacore.a")

lazy val commonSettings = Seq(
  Test / parallelExecution := false,
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked",
    "-no-indent",
    "-rewrite", "-source:3.9")
)

lazy val native = project
  .in(file("native"))
  .enablePlugins(ScalaNativePlugin, BindgenPlugin)
  .settings(commonSettings)
  .settings(
    name      := "s4-yoga-native",
    yogaRoot  := (ThisBuild / baseDirectory).value / "third-party" / "yoga",
    yogaBuild := (ThisBuild / baseDirectory).value / "build" / "yoga",
    bindgenBindings += Binding(yogaRoot.value / "yoga" / "Yoga.h", "scalaui.yoga.generated")
      .withCImports(List("yoga/Yoga.h"))
      .withClangFlags(List(s"-I${yogaRoot.value}")),
    nativeConfig ~= { c =>
      c.withLTO(scala.scalanative.build.LTO.none)
        .withMode(scala.scalanative.build.Mode.releaseFast)
    },
    nativeConfig := {
      val c  = nativeConfig.value
      val yr = yogaRoot.value
      val yb = yogaBuild.value
      c.withCompileOptions(c.compileOptions ++ Seq(s"-I$yr"))
        .withLinkingOptions(c.linkingOptions ++ Seq(s"${yb / "yoga" / "libyogacore.a"}", "-lstdc++"))
    },
    // sn-bindgen generates its C glue in BOTH Compile and Test scopes, so the test
    // binary links two copies and fails with duplicate symbols. Generate in Compile only.
    Test / bindgenBindings := Seq.empty,
    libraryDependencies += "org.scalameta" % "munit_native0.5_3" % "1.3.6" % Test
  )

// No JVM sub-project: `com.facebook.yoga:yoga` is published as an Android **AAR**
// (packaging=pom, -debug/-release classifiers, depends on Facebook SoLoader), not a
// JVM jar, so it cannot be consumed by a desktop-JVM sbt project at all. See REPORT.md
// for the Android route recommendation and the missing-x86_64-ABI problem.

lazy val root = project.in(file(".")).aggregate(native).settings(publish / skip := true)
