ThisBuild / scalaVersion := "3.9.0"

lazy val app = project
  .in(file("."))
  // thicket-renderer-android at the plugin's own version, `-release 17`, the SDK's android.jar
  // on the compile classpath, and `thicketApk`: the jars into android/app/libs, then Gradle.
  .enablePlugins(ThicketAndroidPlugin)
  .settings(
    name := "hello-thicket",
    // Only needed while the artefacts come from `publishLocal`.
    resolvers += Resolver.defaultLocal
  )
