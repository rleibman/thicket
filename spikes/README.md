# Spikes

One directory per spike; each is self-contained. Read `../CLAUDE.md` first, then the
spike's `BRIEF.md`. Write `REPORT.md` when done (format in `CLAUDE.md`).

| Dir | Question | Machine | Status |
|---|---|---|---|
| `s5-signals/` | Cross-platform fine-grained signals in pure Scala 3 | Linux | **PASS** (2026-09-18) |
| `s2-android/` | Scala 3 on Android with R8, in 2026 | Linux | **PASS-WITH-RISK** (2026-09-18) |
| `s1-native-ios/` | Does Scala Native run on iOS (simulator + device)? | macOS | **PASS-WITH-RISK** (2026-09-19) |
| `s4-yoga/` | Yoga layout from Scala Native and from the JVM | Linux | **PASS-WITH-RISK** (2026-09-18) |
| `s3-swift-shim/` | Scala Native ⇄ Swift C-ABI shim: ergonomics and cost | macOS | **PASS** (2026-09-19) |
| `s7-gtk4/` | GTK4 renderer smoke test with existing bindings | Linux | **PASS** (2026-09-18) |
| `s8-zio-ios/` | ZIO 2 runtime on Scala Native on iOS | macOS | **PASS** (2026-09-19) |
| `s6-calibration/` | Baselines: ScalaFX+Gluon on iPhone, Slinky-native/Expo | macOS | not run (optional) |

`_template/` holds the shared `.scalafmt.conf` and a minimal `project/build.properties`
to copy into a new spike.

**Gate (docs/08 §8.1): S1 ∧ S2 ∧ S3 all pass → GO.** Synthesis and decision:
[`docs/10-phase-0-findings.md`](../docs/10-phase-0-findings.md).
