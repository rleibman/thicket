import scala.sys.process.*

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S7"

lazy val gtk4 = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "s7-gtk4",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-source:3.9"),
    libraryDependencies += "com.indoorvivants.gnome" % "gtk4_native0.5_3" % "0.2.6",
    nativeConfig ~= { c =>
      val cflags = "pkg-config --cflags gtk4".!!.trim.split(" ").filter(_.nonEmpty).toSeq
      val ldflags = "pkg-config --libs gtk4".!!.trim.split(" ").filter(_.nonEmpty).toSeq
      c.withLTO(scala.scalanative.build.LTO.none)
        .withMode(scala.scalanative.build.Mode.debug)
        .withCompileOptions(c.compileOptions ++ cflags)
        .withLinkingOptions(c.linkingOptions ++ ldflags)
    }
  )
