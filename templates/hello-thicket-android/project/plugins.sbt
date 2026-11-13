// One plugin. The Thicket dependencies, `-release 17`, the SDK's android.jar for compiling,
// and the `thicketApk` task that hands the jars to the Gradle project in android/ all come
// with `enablePlugins(ThicketAndroidPlugin)` in build.sbt.
//
// `THICKET_VERSION` is what `bin/verify-getting-started-android.sh` sets, so this template is
// checked against the working tree rather than going stale next to it. A plugin version cannot
// be read from build.sbt, so it is named here as well as there.
addSbtPlugin("net.leibman" % "sbt-thicket" % sys.env.getOrElse("THICKET_VERSION", "0.2.0"))

// Only needed while the artefacts come from `publishLocal`. Drop it once you depend on a release.
resolvers += Resolver.defaultLocal
