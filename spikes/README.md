# Spikes

One directory per spike; each is self-contained. Read `../CLAUDE.md` first, then the
spike's `BRIEF.md`. Write `REPORT.md` when done (format in `CLAUDE.md`).

| Dir | Question | Machine | Status |
|---|---|---|---|
| `s5-signals/` | Cross-platform fine-grained signals in pure Scala 3 | Linux | **PASS** (2026-09-18) |
| `s2-android/` | Scala 3 on Android with R8, in 2026 | Linux | **PASS-WITH-RISK** (2026-09-18) |
| `s1-native-ios/` | Does Scala Native run on iOS (simulator + device)? | macOS | not started |
| `s4-yoga/` | Yoga layout from Scala Native and from the JVM | Linux | **PASS-WITH-RISK** (2026-09-18) |
| `s3-swift-shim/` | Scala Native ⇄ Swift C-ABI shim: ergonomics and cost | macOS | not started |
| `s7-gtk4/` | GTK4 renderer smoke test with existing bindings | Linux | not started |
| `s8-zio-ios/` | ZIO 2 runtime on Scala Native on iOS | macOS | not started |
| `s6-calibration/` | Baselines: ScalaFX+Gluon on iPhone, Slinky-native/Expo | macOS | optional |

`_template/` holds the shared `.scalafmt.conf` and a minimal `project/build.properties`
to copy into a new spike.
