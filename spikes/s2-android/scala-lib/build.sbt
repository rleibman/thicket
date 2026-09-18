// S2: builds a plain JAR containing a Scala 3 Android Activity. The Android SDK's
// android.jar is Provided — it exists on the device, must not be packaged.

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.scalaui"
ThisBuild / version      := "0.0.0-S2"

val androidHome     = sys.env.getOrElse("ANDROID_HOME", sys.props("user.home") + "/Android/Sdk")
val androidPlatform = "android-36"

lazy val scalaLib = project
  .in(file("."))
  .settings(
    name := "s2-scala-lib",
    // S2 finding: Scala 3.9.0 rejects -release 8, 9, 11 and 16. Minimum is **17**, so
    // an Android app built with Scala 3.9 needs AGP Java-17 desugaring. See REPORT.md.
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release", "17"),
    // sbt 2 finding: Classpath entries are `HashedVirtualFileRef`, not `File`, so
    // neither `Attributed.blank(file(...))` nor `fileConverter.toVirtualFile` can be
    // appended to `unmanagedJars` any more. Falling back to sbt's `lib/` convention:
    // `lib/android.jar` is a symlink to the SDK (created by setup.sh, gitignored).
    // No Scala library on the classpath at runtime other than what we ship in the JAR;
    // the app module puts scala3-library on its own compile/runtime classpath.
    autoScalaLibrary := true
  )
