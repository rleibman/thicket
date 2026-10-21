# Spikes

Phase 0's experiments, **archived** (2026-10-20, review decision D6). Each directory keeps its
`BRIEF.md` and `REPORT.md` — the question and what was measured, which the docs and the code
comments cite — and nothing else. The code was removed: it was not built by the root build,
nothing depended on it, ~160 files of it still used the old `dev.scalaui` organisation, and one
directory carried a committed jar.

**The code is in git.** Every spike as it was is at commit `8eccd90` (and earlier):

```bash
git checkout 8eccd90 -- spikes/s9-zio-bridge-ios   # one spike, back in the working tree
git archive 8eccd90 spikes | tar -x -C /tmp         # all of them, somewhere else
```

| Dir | Question | Machine | Status |
|---|---|---|---|
| `s5-signals/` | Cross-platform fine-grained signals in pure Scala 3 | Linux | **PASS** (2026-09-18) |
| `s2-android/` | Scala 3 on Android with R8, in 2026 | Linux | **PASS-WITH-RISK** (2026-09-18) |
| `s1-native-ios/` | Does Scala Native run on iOS (simulator + device)? | macOS | **PASS-WITH-RISK** (2026-09-19) |
| `s4-yoga/` | Yoga layout from Scala Native and from the JVM | Linux | **PASS-WITH-RISK** (2026-09-18) |
| `s3-swift-shim/` | Scala Native ⇄ Swift C-ABI shim: ergonomics and cost | macOS | **PASS** (2026-09-19) |
| `s7-gtk4/` | GTK4 renderer smoke test with existing bindings | Linux | **PASS** (2026-09-18) |
| `s8-zio-ios/` | ZIO 2 runtime on Scala Native on iOS | macOS | **PASS** (2026-09-19) |
| `s6-calibration/` | Baselines: ScalaFX+Gluon on iPhone, Slinky-native/Expo | macOS | **PASS (partial)** (2026-09-20) |
| `s9-zio-bridge-ios/` | The ZIO bridge on iOS (#2): fibres, posts and the handle table under load | macOS | **PASS** |

`_template/` held the shared `.scalafmt.conf`; the one in use is the repository root's.

**Gate (docs/08 §8.1): S1 ∧ S2 ∧ S3 all pass → GO.** Synthesis and decision:
[`docs/10-phase-0-findings.md`](../docs/10-phase-0-findings.md).
