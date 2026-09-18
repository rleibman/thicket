# Decisions (binding for phase 0)

These are the choices an agent would otherwise make arbitrarily. Change them by
adding a dated entry to the Decision log at the bottom, not by editing the table.

## Build, language, versions

| Topic        | Decision                                                                                                                                                                                                                                                      | Notes                                                              |
|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| **Version policy** | **Always target the latest stable Scala and the latest stable sbt.** Not the LTS line, not a pinned older release: newest stable. Re-resolve at the start of every spike/milestone with the commands below and record what you used in `REPORT.md`. If a dependency blocks the newest version, that is a finding to report, not a reason to silently pin. | User directive, 2026-09-18. |
| Build tool | **sbt 2.0.9** (`sbt.version=2.0.9`). Verified working for a JVM/JS/Native crossproject in S5. mill/scala-cli support is a later tooling task (P-06). | See sbt-2 notes below. |
| Scala | **3.9.0** (latest stable; 3.10.0 is RC). Verified on all three backends in S5. Re-check: `cs complete-dep org.scala-lang:scala3-compiler_3:` | |
| Scala Native | **0.5.12**, plugin `org.scala-native:sbt-scala-native:0.5.12`. Publishes `nscplugin_3.9.0`, so it supports Scala 3.9. Re-check compatibility with `cs complete-dep org.scala-native:nscplugin_<scalaVersion>:` before bumping Scala. | Scala Native's compiler plugin is published per *exact* Scala version — this is the usual thing that blocks a Scala upgrade. |
| Scala.js | **1.22.0**. Scala 3 has Scala.js support in the compiler itself (no separate compiler plugin), so it does not constrain the Scala version. | |
| sbt-crossproject | 1.4.0 (`org.portable-scala`, both `sbt-scalajs-crossproject` and `sbt-scala-native-crossproject`). | |
| ZIO | **2.1.26** (`dev.zio::zio`, `zio-streams`); `_native0.5_3` artefacts confirmed. | S8, M0. |
| munit | **1.3.6**; `munit-scalacheck` is versioned separately at **1.3.1**. | All spikes. |
| scalafmt | 3.11.5 (`.scalafmt.conf` in `spikes/_template/`). | |
| JDK | JDK 21 LTS for Android work. The Linux box has **OpenJDK 23-ea** on `PATH`; S5 ran fine on it, but AGP requires a non-EA JDK ≥ 17 — install 21 via `cs java --jvm temurin:21` for S2. | |
| Android | min SDK 26, compile/target SDK **36**, AGP **9.4.1**, Gradle **9.7.1**, build-tools 37.0.0. SDK installed at `~/Android/Sdk` (2026-09-18) with an `android-36 google_apis x86_64` image; AVD `s2test`. **Three mandatory settings for Scala** — `android.enableR8.fullMode=false`, the `*$lzy*` keep rule, and excluding `**/*.tasty` from packaging (S2). | Note `platforms;android-37` is listed by sdkmanager but is not installable. |
| iOS/macOS | Xcode latest stable; deployment target iOS 17 / macOS 13; Swift 6 language mode is *not* required in the shim (use 5 mode to avoid strict-concurrency noise). | |
| C toolchain | clang (present: 21 on Linux; Apple clang on Mac). Scala Native on Linux needs `zlib1g-dev` and **`libunwind-dev`** (installed 2026-09-18). | |

### sbt 2 notes (from S5 — read before starting a build)

- **Plugin suffix is `_sbt2_3`**, not `_3_2.0`. All four plugins this project needs exist:
  `org.scala-js:sbt-scalajs`, `org.scala-native:sbt-scala-native`,
  `org.portable-scala:sbt-scalajs-crossproject`, `org.portable-scala:sbt-scala-native-crossproject`.
- **`%%%` does not exist.** There is no `sbt-platform-deps` for sbt 2. Write cross-platform
  dependencies per platform with explicit artefact suffixes, e.g.
  `"org.scalameta" %% "munit" % v` (JVM), `"org.scalameta" % "munit_sjs1_3" % v` (JS),
  `"org.scalameta" % "munit_native0.5_3" % v` (Native). See `spikes/s5-signals/build.sbt`.
- **`sbt test` is incremental** and runs *nothing* when sources are unchanged — which reads as a
  pass. Use **`testOnly *`** in briefs, scripts and CI.
- **`-Xfatal-warnings` is deprecated** in Scala 3.9: use `-Werror`.
- **Minimum `-java-output-version` for Scala 3.9 is 17** (S2). 8/9/11/16 are rejected.
- **`unmanagedJars` cannot take a `File`** (classpaths are `HashedVirtualFileRef`); use the
  `lib/` convention. Built artifacts are symlinks into `~/.cache/sbt/v2/cas` — `readlink -f` them.
- Scala.js `runMain` is not available; set `Compile / mainClass` and
  `scalaJSUseMainModuleInitializer := true`, then use `run`.


## Naming and layout

| Topic               | Decision                                                                                                                                                                                                                                                     |
|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Working name        | `scala-ui` (repo), root package **`scalaui`**. Renaming is a product question (Q14) — do not bikeshed.                                                                                                                                                       |
| Licence             | Apache-2.0. Add `LICENSE` at M0, not during spikes.                                                                                                                                                                                                          |
| Repo layout         | `docs/` plan · `spikes/sN-<slug>/` one self-contained sbt (or Gradle/Xcode) project per spike, each with `BRIEF.md` and `REPORT.md` · `modules/` framework code, **empty until M0** · `shims/` Swift/C++ shims (M1+) · `tooling/` plugins/CLI (M5).          |
| Spike independence  | Spikes never depend on each other's code. Copying a file between spikes is allowed; importing a spike as a dependency is not.                                                                                                                                |
| Module naming (M0+) | `scala-ui-core`, `scala-ui-signals`, `scala-ui-renderer-api`, `scala-ui-effect-api`, `scala-ui-effect-zio`, `scala-ui-renderer-<platform>`, `scala-ui-layout-yoga`. Group id decided at M0.                                                                  |
| Code style          | Scala 3 syntax: significant indentation, `given`/`using`, `enum`, no `implicit` keyword, no `null`, no exceptions for control flow in library code. Scalafmt with `runner.dialect = scala3`; add `.scalafmt.conf` per spike (copy from `spikes/_template/`). |
| Tests               | munit (cross-published for all three backends) + munit-scalacheck for property tests.                                                                                                                                                                        |

## Execution order for phase 0

| Order | Spike                      | Machine                   | Parallel with |
|-------|----------------------------|---------------------------|---------------|
| 1     | **S5 signals**             | Linux                     | S2            |
| 1     | **S2 Android**             | Linux (after SDK install) | S5            |
| 2     | **S1 Scala Native on iOS** | Mac                       | S4            |
| 2     | S4 Yoga                    | Linux                     | S1            |
| 3     | S3 Swift shim              | Mac (after S1 pass)       | S7            |
| 3     | S7 GTK4                    | Linux                     | S3            |
| 4     | S8 ZIO on iOS              | Mac (after S1 pass)       | —             |
| any   | S6 calibration             | Mac                       | optional      |

Go decision (see `08` §8.1) is made after S1, S2, S3 have reports.

## Decision log

- 2026-09-18 — **Apple spikes run on the iOS simulator only**; no physical iPhone is
  available. Sufficient for go/no-go (the question is whether the Scala Native runtime
  works on iOS at all); device signing, arm64-only codegen and real memory pressure are
  deferred to M1. S1's brief updated accordingly.
- 2026-09-18 — **Repo hosted on the user's Forgejo**
  (`ssh://forgejo@forgejo.leibmanland.com/rleibman/scala-ui.git`), matching meal-o-rama,
  rather than GitHub. Push-to-create is disabled server-side, so the repo must be created
  in the web UI before the first push.
- 2026-09-18 — **Version policy set by the user: always target the latest stable Scala and sbt,
  for all projects.** Consequently sbt 1.13→**2.0.9** and Scala 3.3.8 LTS→**3.9.0**. The earlier
  claim that the Scala.js/Native/crossproject plugins are sbt-1-only was **wrong** — they publish
  under the `_sbt2_3` suffix. S5 verified the whole JVM/JS/Native crossproject on sbt 2.0.9 +
  Scala 3.9.0. Known sbt-2 gaps recorded above (`%%%`, incremental `test`).
- 2026-09-18 — `libunwind-dev` installed on the Linux box (Scala Native prerequisite).
- 2026-09-18 — Initial decisions written during planning. Effect strategy: core is
  effect-free; ZIO bridge is first-class from M0 (see `07` §7.13). Hardware: Linux
  box (primary), macOS laptop (Apple spikes), Windows box (later); iOS device
  possibly available, simulator is the baseline.
