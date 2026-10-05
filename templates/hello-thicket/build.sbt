import scala.sys.process.*

// The version to depend on. `bin/verify-getting-started.sh` overrides it with whatever the
// working tree publishes, so this template is checked against the current code rather than
// going stale next to it.
lazy val thicketVersion = sys.env.getOrElse("THICKET_VERSION", "0.1.0")

ThisBuild / scalaVersion := "3.9.0"

lazy val app = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "hello-thicket",
    libraryDependencies ++= Seq(
      "dev.thicket" % "thicket-core_native0.5_3"         % thicketVersion,
      "dev.thicket" % "thicket-renderer-gtk_native0.5_3" % thicketVersion
    ),
    resolvers += Resolver.defaultLocal,
    // Everything below is what a consumer must currently copy from thicket's own build:
    // the GTK compile/link flags and the Scala Native mode. Nothing publishes it.
    nativeConfig ~= { c =>
      val cflags = "pkg-config --cflags gtk4 libadwaita-1".!!.trim.split(" ").filter(_.nonEmpty).toSeq
      val ldflags = "pkg-config --libs gtk4 libadwaita-1".!!.trim.split(" ").filter(_.nonEmpty).toSeq
      c.withLTO(scala.scalanative.build.LTO.none)
        .withMode(scala.scalanative.build.Mode.debug)
        .withGC(scala.scalanative.build.GC.immix)
        .withCompileOptions(c.compileOptions ++ cflags)
        .withLinkingOptions(c.linkingOptions ++ ldflags)
    },
    Compile / mainClass := Some("hello.Main")
  )
