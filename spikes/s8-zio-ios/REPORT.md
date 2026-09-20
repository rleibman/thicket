# S8 — ZIO 2 on Scala Native on iOS — REPORT

**Date:** 2026-09-19
**Machine:** macOS (Darwin 27.0.0), Apple Silicon arm64
**Target:** iOS **simulator** only (iPhone 17, iOS 27.0) — no physical device available
**Depends on:** S1 PASS, S3 PASS. **Gate:** no.

| Tool | Version |
|---|---|
| Xcode / iOS SDK | 27.0 (27A266a) / iPhoneSimulator27.0.sdk |
| Swift | 6.4 (swiftlang-6.4.0.34.1), Swift 5 language mode |
| sbt / Scala / Scala Native | 2.0.9 / 3.9.0 / 0.5.12 |
| **ZIO** | **2.1.26** (`zio_native0.5_3`, `zio-streams_native0.5_3`) |
| **scala-java-time** | **2.7.0** (`scala-java-time_native0.5_3`) — see finding 1 |

---

## Result: PASS

Every pass criterion is met, twice over, in two independent 10-minute runs:

- Runtime starts: `Runtime.default` init **1.16–1.21 ms** against a ≤ 50 ms target.
- Fibers, streams, refs and interruption all behave.
- **37,500 / 37,500 ticks delivered (100.0%)** against a ≥ 95% target.
- No crash, no hang, no GC warning over 10 minutes.
- ZIO adds **+3.25 MB RSS**, against a ≤ 10 MB target.

None of the brief's PASS-WITH-RISK conditions apply: it does **not** need a single-threaded
executor (the default multi-threaded runtime works; `UiExecutor` is used only for the UI
path), and init is nowhere near 50 ms. The immix-only constraint is S1's, not ZIO's.

Size was the one open worry and it resolved favourably once measured properly. ZIO takes the
stripped app to 7.73 MB with LTO off — over N-03's 6 MB — but **`releaseFast` + `LTO.full`
brings it to 5.75 MB, inside budget, with every test still passing**. Detail in finding 5.

---

## Measurements

### Pass criteria

| Criterion | Target | Run A | Run B |
|---|---|---|---|
| `Runtime.default` init | ≤ 50 ms | **1.16 ms** | **1.21 ms** |
| `UiExecutor` runtime init | — | 0.08 ms | 0.08 ms |
| RSS added by ZIO | ≤ 10 MB | **+3.27 MB** | **+3.25 MB** |
| 100 fibers, checksum | correct | **4670** ✓ | **4670** ✓ |
| Ticks delivered | ≥ 95% | **37500/37500 (100.0%)** | **37500/37500 (100.0%)** |
| Crash/hang over 10 min | none | **none** | **none** |

The fibre checksum (`sum of (i*i) % 97` over 1..100) was computed independently in Python as
**4670** before the run, so this is a correctness check, not a self-consistency one.

### Everything else the brief asks for

| Question | Answer |
|---|---|
| Is javalib's `Clock` accurate on iOS? | **Yes.** `ZIO.sleep(1.second)` measured 1001.2 ms and 1002.3 ms — drift **+1.2 / +2.3 ms**. |
| Does `attemptBlocking` get threads on iOS? | **Yes.** Ran on `zio-default-blocking-1`, a real pool thread. |
| Does interruption work promptly? | **Yes.** `fiber.interrupt` returned in **0.06 ms**; `Scope` close ran its finalizer (`released=true`). |
| Does `SubscriptionRef` work? | **Yes.** `.changes` consumed on the UI executor saw `0,1,2,3,4`. |
| CPU while ticking | **2.1%** (13.8 s CPU / 652.7 s wall). |
| Do the ZIO scheduler and UIKit main thread interact badly? | **No** — see finding 4. |

### Memory over the 10-minute tick run (sampled every 60 frames, 625 samples)

| frame | 0 | 600 | 1200 | 1800 | 3600 | 9600 | 37440 |
|---|---|---|---|---|---|---|---|
| RSS (MB) | 161.0 | 168.4 | 174.7 | 177.0 | 181.9 | 181.9 | **182.0** |

RSS climbs during the first ~minute and then is **flat at 181.9–182.0 MB for the remaining
~34,000 frames**. That is immix sizing its heap to the working set, not a leak. The
handle table returned to **0 entries**, so the per-task registrations the executor makes are
all reclaimed.

(The ~150 MB baseline is UIKit; S1's UI-less harness sat at 13.5 MB.)

### Tick rate: 100% delivered, but not at 62.5 Hz

| run | ticks | wall | effective rate |
|---|---|---|---|
| A | 37,500 | 677.8 s | **55.3 Hz** |
| B | 37,500 | 652.7 s | **57.5 Hz** |

Every tick arrives — nothing is dropped — but 37,500 of them take 653–678 s rather than 600.
`ZStream.tick(16.millis)` is a fixed-**delay** schedule: the period is 16 ms *plus* whatever
the body costs, so the stream drifts behind wall-clock by the work it does. At 60 Hz that is
an 8–12% shortfall. **This matters for A-05c**: "60 Hz" is not what `ZStream.tick` gives you.
A frame loop that must track wall-clock needs `Schedule.fixed` semantics or a display-link
driven source, not `tick`.

### Size (the finding that matters)

| Build | static archive | app binary, stripped |
|---|---|---|
| S3 (shim, no ZIO), debug | 5,284,448 B | 3,147,592 B (3.00 MB) |
| S8 (shim + ZIO), debug, LTO off | 17,012,192 B | 10,452,408 B (9.97 MB) |
| S8 (shim + ZIO), releaseFast, LTO off | 16,222,416 B | 8,103,248 B (7.73 MB) |
| **S8, releaseFast + `LTO.full`** | 17,018,672 B | **6,032,392 B (5.75 MB)** |

**The shipping configuration fits N-03.** `LTO.full` saves a further 1.97 MB over
`releaseFast` alone and the resulting binary was installed and run: all six tests passed,
3,750/3,750 ticks delivered, checksum correct, no errors. For reference S1's bare runtime was
1.90 MB stripped, so ZIO + `scala-java-time` + the shim cost roughly **+3.9 MB fully
optimised** — not the +5 MB that LTO-off measurement suggested.

---

## What was built

```
spikes/s8-zio-ios/
  shim/                             copied from S3 (spikes never depend on each other)
  scala-lib/src/main/scala/scalaui/s8/
    generated.scala                 sn-bindgen output for the shim header
    Handles.scala                   handle table + registerOneShot for executor tasks
    GcState.scala                   S3's Managed/Unmanaged guard
    UiExecutor.scala                zio.Executor posting to the UIKit main thread
    Main.scala                      scalaui_zio_start + the six tests
  ios-app/Sources/Host.swift        UIScene-based host
  ios-app/build-app.sh              swiftc shim+host, link .a, install, launch
  regen-bindings.sh
```

Reproduce:

```
sbt scalaLib/nativeLink && cd ios-app && ./build-app.sh
SIMCTL_CHILD_S8_TICK_SECONDS=600 xcrun simctl launch --console-pty "iPhone 17" dev.scalaui.s8app
```

---

## Problems hit

### 1. ZIO does not link on Scala Native without a `java.time` polyfill (**solved**)

The very first link failed:

```
Unknown type java.time.Duration, referenced from:
  zio.DurationSyntax$.asDuration$extension ... zio.Fiber$ ... zio.Runtime$
Unknown static method java.time.Instant.now
Unknown type java.time.temporal.{ChronoUnit, Temporal, TemporalUnit}
```

`zio.Duration` **is** `java.time.Duration`, and Scala Native 0.5.12's javalib contains no
`java.time` at all (S1 finding 3). ZIO's published Native artefacts do not declare a
substitute, so the dependency has to be added downstream:

```scala
"io.github.cquiroz" % "scala-java-time_native0.5_3" % "2.7.0"
```

With it, everything links and works. **This is a required, undocumented dependency for
anyone using ZIO on Scala Native** — and it is a meaningful part of the size cost.

### 2. `zio.System` shadows `java.lang.System` (**trivial, but worth knowing**)

`import zio.*` brings `zio.System` into scope, so `System.nanoTime()` stops compiling with
"value nanoTime is not a member of object zio.System". Any timing code in a file that
imports ZIO wholesale needs `java.lang.System.nanoTime()`.

### 3. `Thread.activeCount()` is not meaningful on Scala Native

Reported `threads 14 -> 1` across an `attemptBlocking` call — the count went *down* while a
blocking pool thread was being used. The thread *name* (`zio-default-blocking-1`) is reliable
evidence that the pool exists; the count is not. Do not build diagnostics on it.

### 4. Backgrounding: no interaction problem, and no catch-up burst (**good news**)

The brief asks whether the ZIO scheduler and UIKit's main thread interact badly. Tested by
running the tick stream, bringing another app to the foreground, waiting 40 s, then returning:

| phase | frame |
|---|---|
| before backgrounding | 1320 |
| after 40 s backgrounded | 1380 |
| after 30 s back in foreground | 3060 |

iOS suspends the app, as it does any app: only ~60 frames trickled through in 40 s. On
resume the stream continues at its **normal** rate (1680 frames in 30 s ≈ 56 Hz) rather than
firing a burst of ~2,200 queued ticks — a consequence of the same fixed-delay semantics that
costs the 8% rate shortfall above. No hang, no priority inversion, no crash, no GC warning.

**The fixed-delay behaviour is a liability for frame pacing and an asset for backgrounding.**
Whatever replaces `tick` for 60 Hz must not reintroduce a catch-up stampede on resume.

### 5. Size: over budget until LTO is turned on, then under it (**solved**)

With LTO off — which is what S1 used and what this spike inherited — `releaseFast` gives
7.73 MB stripped against N-03's 6 MB. Enabling `LTO.full` brings it to **5.75 MB**, and the
optimised binary passes every test.

Two things bite when switching it on:

- **`LTO.full` needs `-lc++` at the final link.** Without it the link fails with
  `Undefined symbols: std::exception::what() const, std::exception::~exception()` referenced
  from `scalanative::ExceptionWrapper` — the C++ runtime dependency is invisible until LTO
  merges the objects.
- **Use `full`, not `thin`.** Scala Native's own `Validator` warns that "LTO.thin is unstable
  on MacOS, it can lead to compilation errors. Consider using LTO.full (legacy, slower) or
  LTO.none". It is a warning, not a rejection, so `thin` will build and may then fail oddly.

`LTO.full` is meaningfully slower to link, so the spike keeps `LTO.none` as the default for
iteration and documents the shipping recipe in `build.sbt`.

### 6. A subscription race in my own test, kept because the lesson generalises

`SubscriptionRef.changes` emits the current value on subscription and then updates. Forking
the consumer and immediately calling `set` let the first `set` land before the subscription
existed, so `take(5)` waited forever and the test **hung rather than failed**. Fixed with a
100 ms head start, and every test now carries a 30 s deadline that reports itself. Any
`SubscriptionRef`-based binding in the framework has the same hazard at startup.

### 7. sbt 2 caches env-driven settings harder than S1 found

S1 recorded that the sbt server captures the environment at startup. S8 found it is worse:
after `pkill`-ing the server **and** confirming `S8_MODE` was absent from the new server's
environment, `nativeConfig` still reported `release-fast`. sbt 2 caches the evaluated setting
in its content-addressed store and `sys.env` is not part of the cache key.

**Correction, from re-testing this in S6:** the stale value survives *more* than first
reported here. It survived killing the server, `touch build.sbt`, a real content edit to
`build.sbt`, **and `rm -rf target/out`** — S6 built an iOS-*device* archive while the build
said simulator, and only noticed because the linker refused the object files. The CAS lives
in `~/.cache/sbt/v2/cas`, not under `target/`, which is why clearing the project output does
nothing. What actually fixes it is **removing the `sys.env` read** and writing the value
literally.

**Conclusion: do not drive build configuration from environment variables in sbt 2 at all.**
S1 reached this by a different route; S8 and S6 confirm it is neither a server-lifetime issue
nor something a clean can rescue.

---

## Recommendations for the plan

1. **`docs/decisions.md` — record `scala-java-time` as a required companion to ZIO on
   Native.** `zio.Duration` is `java.time.Duration`, Scala Native has no `java.time`, and ZIO
   does not pull a substitute in itself. Without it nothing links. This belongs next to the
   ZIO row in the versions table, pinned at 2.7.0.

2. **`docs/07-technical-design-ideas.md` §7.13 / A-05c — correct the 60 Hz claim.**
   `ZStream.tick(16.millis)` delivers 100% of its ticks but at **55–58 Hz**, because it is
   fixed-delay rather than fixed-rate. A renderer that must track wall-clock needs
   `Schedule.fixed` or a `CADisplayLink`-driven source. Note the trade: fixed-delay is also
   why backgrounding produces no catch-up stampede, so whatever replaces it must handle
   resume deliberately.

3. **`docs/decisions.md` — make `releaseFast` + `LTO.full` + `-lc++` the recorded shipping
   configuration for Apple targets.** It is the difference between 7.73 MB (over N-03) and
   5.75 MB (under it), and the `-lc++` requirement is invisible until you try. The §7.13
   decision to make the ZIO bridge first-class from M0 **stands**: ZIO on iOS costs 1.2 ms
   init, +3.25 MB RSS, 2.1% CPU at 60 Hz, and ~3.9 MB of fully-optimised binary, which the
   budget accommodates. No need to demote it to opt-in on size grounds.

4. **Reuse the `UiExecutor` pattern as-is.** 35 lines: a `zio.Executor` whose `submit` posts a
   one-shot handle-table id through `sui_run_on_main`. It delivered 37,504 tasks with the
   table returning to zero entries. The one-shot registration is essential — a table that does
   not shed entries at 60 Hz is a leak with a clock on it.

5. **`docs/09-open-questions.md` — `Thread.activeCount()` is unreliable on Scala Native**, so
   any runtime diagnostics the framework exposes should be built on thread names or ZIO's own
   metrics, not JVM thread-count APIs.
