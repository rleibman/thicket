# 18. An Android app outside this repository

Status: **built, 2026-11-12 (#164): option A, a debug APK by default (§18.6).** `ThicketAndroidPlugin`, `templates/hello-thicket-android` and `bin/verify-getting-started-android.sh`; measured in `tools/sbt-thicket/README.md`. Not in a release yet: 0.2.0 predates it.

## 18.1 The gap

On GTK, macOS and the iOS simulator, a developer copies a template, adds one sbt plugin, and
has an app built from Maven Central alone. `bin/verify-getting-started*.sh` check exactly that.
Android has none of it:

- **No template.** `examples/todo-android` and `examples/gallery/android` are built by
  `build.sh` scripts that run sbt **in this repository**, and copy the jars it produced into a
  Gradle project's `app/libs`.
- **No plugin.** `sbt-thicket` has `ThicketGtkPlugin`, `ThicketMacPlugin` and `ThicketIosPlugin`,
  but nothing for Android. The settings an Android app needs live only in our example builds:
  `-release 17` and the dependency on `thicket-renderer-android`.
- **Three mandatory settings nobody would find.** S2 measured them (`docs/09` §9.3c), and
  without them the failures happen at *run time*, with errors that name no Scala concept:
  1. `android.enableR8.fullMode=false`;
  2. the `*$lzy*` keep rule in `scala3.pro`;
  3. excluding `**/*.tasty` from packaging, without which the APK goes from 81 KB to 3.89 MB.

  Today they reach an app only by copying our files.

`docs/12` §12.7's 0.1 bar starts with Android, and the owner's own platform is Android. So this
is the largest of the gaps left.

## 18.2 What is fixed regardless of the option

- **Scala cannot be compiled inside the Android Gradle module.** Gradle's `scala` plugin
  applies `JavaPlugin`, which collides with AGP's `implementation` configuration. That was
  measured in S2, not assumed. So *something else* compiles the Scala, and Gradle consumes it.
- **Gradle still does the packaging:** dex, R8, resources, the manifest, signing, the APK or
  AAB. Nothing else does that job well, and Android developers expect it.
- **The Thicket jars come from Maven Central** (`thicket-renderer-android_3` and the rest, since
  0.2.0). Gradle can resolve them itself. Only the app's *own* classes need handing over.
- **Verification:** a script builds the template **outside** the repo against Central, assembles
  the APK, installs it on the `s2test` emulator and checks it stays up. That is the same
  standard as GTK's and Apple's.

## 18.3 Option A — an sbt plugin, and a Gradle shell the app owns (recommended)

The shape `docs/07` §7.9 planned.

- **`ThicketAndroidPlugin` in `sbt-thicket`:**
  - adds `thicket-renderer-android` at the plugin's own version;
  - sets `-release 17`;
  - adds a task, `thicketAndroidJars`, that writes the app's runtime classpath into the Gradle
    project's `libs/`. sbt 2 has no usable classpath export: our `build.sh` scripts work around
    it by packaging each module. The plugin can ask the build for it properly instead.
- **`templates/hello-thicket-android`:**
  - an sbt project, `scala/`, holding the Activity (`extends ThicketActivity`);
  - a Gradle project, `app/`, holding the manifest, `scala3.pro` and the three mandatory
    settings, with a Gradle wrapper;
  - after copying, the Gradle shell belongs to the app (P-04): it is the app's to change.
- **One command to build, `sbt thicketApk`:** runs `thicketAndroidJars`, then `gradlew
  assembleRelease`, and prints the APK's size. An optional `thicketInstall` runs `adb install`
  and launches the app.

**For:** it matches GTK and Apple: one plugin, one template, the same `sbt-thicket` version for
everything. A cross-platform app is one sbt build with an Android subproject. The verify script
has the same shape as the others.
**Against:** two build tools for an Android developer, and Android Studio sees only the
Gradle half. Editing Scala happens in IntelliJ or Metals against the sbt build.
**Cost:** the plugin, about 150 lines like `ThicketGtkPlugin`; the template, copied and cut down
from `todo-android`; the verify script; a gallery-style self-test is not needed. Roughly one PR.

## 18.4 Option B — Gradle only, with a separate Scala module

A Gradle multi-project with two modules:
- `:shared`, a plain JVM module (`java-library` + Gradle's `scala` plugin) compiled against
  `android.jar` as `compileOnly`;
- `:app`, the AGP module, depending on `:shared`.

The `scala` plugin is not applied to an Android module, so S2's collision does not arise. Thicket
comes from Central through ordinary Gradle dependencies.

**For:** one build tool, Android Studio builds and runs everything, and it is familiar to
Android developers.
**Against:**
- **Unmeasured.** S2 tested only the shape where the plugin goes *inside* the Android module.
  Gradle's Zinc integration for Scala 3, compiling against `android.jar`, and Android Studio's
  handling of a Scala module are all unknown.
- A cross-platform app would need sbt for GTK and Apple anyway, so it would have two builds for
  the same shared code.
- It also leaves `sbt-thicket` with nothing to say about Android.

**Cost:** a spike first, roughly half a day, before anything can be promised.

## 18.5 Option C — a template only, no plugin

Copy `examples/todo-android` into `templates/hello-thicket-android`, with the Thicket jars
coming from Central and a `build.sh` that runs sbt and then Gradle.

**For:** the cheapest, and nothing new to maintain in `sbt-thicket`.
**Against:**
- It is our `build.sh` workaround shipped to users: packaging each module and copying jars by
  hand.
- Every app carries a copy of the build logic, so a fix to the hand-off reaches nobody.

**Cost:** an afternoon.

## 18.6 Questions for the owner — answered 2026-11-12

1. **Which shape?** A (recommended), B (spike first), or C.
2. **Release or debug by default.**
   - Our examples build `assembleRelease`, signed with the *debug* key, so the size is the real,
     R8-shrunk one.
   - A template is better served by a debug build that installs as is, with release documented.

   Recommended: debug by default, and `thicketApk` taking `release` as an option.
3. **An AAB for the Play Store**, or only an APK for now. Recommended: APK only; the AAB is a
   Gradle task the app can run when it gets to the Store.

**Answers (2026-11-12):**
1. **A**: `ThicketAndroidPlugin` and a template with an app-owned Gradle shell.
2. **A debug APK by default**, with release as an option.
3. **No AAB task.** The Play Store bundle is the app's own Gradle task when it gets there.
