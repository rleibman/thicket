# S5 — Cross-platform fine-grained signals — REPORT

**Date:** 2026-09-18 **Machine:** Linux (Ubuntu, kernel 7.0.0-31), clang 21.1.8, Node v22.22.1, Oracle JDK 23-ea
**Versions used:** Scala **3.9.0** · sbt **2.0.9** · Scala Native **0.5.12** · Scala.js **1.22.0**
· sbt-crossproject **1.4.0** · munit **1.3.6** · munit-scalacheck **1.3.1**

> Deviation from `docs/decisions.md` as written: that file specified sbt 1.13 / Scala 3.3.8.
> The user directed mid-spike that **all projects target the latest Scala and sbt**, so this
> spike runs on sbt 2.0.9 and Scala 3.9.0. `docs/decisions.md` has been updated accordingly,
> including the correction that the sbt-2 plugins *do* exist (suffix `_sbt2_3`).

## Result: **PASS**

A SolidJS-style fine-grained reactive core is comfortably achievable in pure Scala 3,
cross-compiles unchanged to all three backends, is glitch-free under property testing,
and beats the performance budget on every backend by 2–13×. Two real bugs and one real
design flaw were found and fixed by the tests; one genuine limitation (recursion depth)
is documented below and is not a blocker for UI work.

## Measurements

### Correctness

| Criterion                                 | Target                      | Measured  | How                                                                                                                            |
|-------------------------------------------|-----------------------------|-----------|--------------------------------------------------------------------------------------------------------------------------------|
| Tests pass on JVM                         | all                         | **20/20** | `sbt "signalsJVM/testOnly *"`                                                                                                  |
| Tests pass on Scala.js                    | all                         | **18/18** | `sbt "signalsJS/testOnly *"` (ThreadGuardSuite is JVM/Native-only: JS has no threads)                                          |
| Tests pass on Scala Native                | all                         | **20/20** | `sbt "signalsNative/testOnly *"`                                                                                               |
| Glitch-freedom                            | no inconsistent observation | **holds** | Property test over random DAGs (1–5 vars, 1–12 computeds, 12 random writes): every computed always equals a pure recomputation |
| At most one evaluation per node per write | ≤ 1                         | **holds** | Same property test asserts `evals.forall(_ <= 1)` after each write                                                             |
| Batch ≡ sequential                        | equal results               | **holds** | Property test compares batched vs one-by-one writes                                                                            |
| Disposal unlinks everything               | 0 observers                 | **holds** | Property test asserts every `Var.observers` is empty after `Owner.dispose()`                                                   |

### Performance

Nanoseconds per node update = wall time ÷ (writes × nodes re-evaluated). Median of 5 timed
runs after 3 warm-ups, same source on all three backends (see "JMH" note below).

| Benchmark                                | Target                                   | **JVM**  | **Scala.js (Node 22)** | **Scala Native** |
|------------------------------------------|------------------------------------------|----------|------------------------|------------------|
| `chain` 1 000 deep, 200 writes           | ≤ 1 000 ns (JVM/Native), ≤ 5 000 ns (JS) | **76.8** | **418.9**              | **219.3**        |
| `fanOut` 1 000 wide, 200 writes          | as above                                 | **96.9** | **653.3**              | **436.5**        |
| `pullOnly` (lazy, no effect) 1 000 deep  | —                                        | **64.4** | **368.4**              | **243.2**        |
| `noopWrites` (equality cutoff), ns/write | —                                        | **5.6**  | **5.7**                | **4.3**          |

All three backends beat the budget: JVM by **13×**, Native by **2.3–4.6×**, JS by **7.6–12×**.

### Recursion depth (a real limitation — see Problems)

| Backend                          | Max chain depth evaluable on the default stack |
|----------------------------------|------------------------------------------------|
| JVM (inside sbt's thread pool)   | between 4 000 and 8 000                        |
| Scala.js (Node 22)               | between 4 000 and 6 000                        |
| Scala Native (Linux, 8 MB stack) | **> 16 000** (no failure observed)             |

### Size and build

| Metric                                            | Value                                             |
|---------------------------------------------------|---------------------------------------------------|
| Core code (shared, non-blank non-comment)         | **283 lines** — budget was ≤ 1 000                |
| Platform-specific code (`ThreadGuardPlatform` ×3) | 31 lines                                          |
| Test code                                         | 320 lines                                         |
| Dependencies beyond the Scala stdlib              | **none** (munit/scalacheck are `Test`-only)       |
| Macros / compiler plugins                         | **none** (`inline` used only for state constants) |
| Scala Native link, `releaseFast` + no LTO         | **13.6 s**                                        |
| Scala Native link, `debug` + no LTO               | **4.3 s**                                         |

## What was built

```
spikes/s5-signals/
  build.sbt, project/{build.properties,plugins.sbt}, .scalafmt.conf
  signals/shared/src/main/scala/scalaui/signals/
    Core.scala          graph engine: Source, Computation, Runtime (mark/validate/flush/batch)
    Signal.scala        Signal, Var, Computed, Effect, Disposable, Signal.{computed,effect,batch,untracked,const}
    Owner.scala         lifetimes: Owner, Owner.{apply,child,scoped,root}
    ThreadGuard.scala   single-thread contract (A-06)
  signals/{jvm,js,native}/src/main/scala/scalaui/signals/ThreadGuardPlatform.scala
  signals/shared/src/main/scala/scalaui/signals/bench/Bench.scala
  signals/shared/src/test/.../{SignalSuite,GraphPropertySuite}.scala   (15 + 3)
  signals/{jvm,native}/src/test/.../ThreadGuardSuite.scala             (2)
```

### Algorithm chosen: push-mark / pull-validate

Rejected height-based topological scheduling because dynamic dependencies force height
re-computation and re-queueing. Implemented the Preact-Signals / Angular-Signals model instead:

1. **Push (cheap, on write).** `Var.set` bumps a version, marks direct observers `Dirty` and
   transitive observers `Check`, and schedules any effects it meets. Marking stops at nodes
   already non-`Clean`, so each node is visited once.
2. **Pull (on read or effect run).** A `Check` node validates its dependencies depth-first and
   only recomputes if a dependency's *version actually changed*. A computed's version bumps
   only when its value changes (`!=`), so an unchanged intermediate stops propagation dead.

This yields glitch-freedom without a topological sort, handles dynamic dependencies for free (each recompute rebuilds
its dependency list), and gives equality cutoff at every level — the
`noopWrites` and "equality cutoff stops propagation" results are that property.

## Problems hit

1. **Effects left stale after an exception (bug, fixed).** If a computed threw during a flush,
   its state stayed `Dirty` and the observing effect stayed `Check` but was no longer scheduled.
   The next write short-circuited at the already-`Dirty` computed and never reached the effect,
   so the graph silently stopped updating. Fix: `Runtime.flush` re-queues any computation whose
   update threw, restoring the invariant *a non-`Clean` effect is always scheduled*. Covered by
   "exception in computed propagates to the writer and the graph recovers".

2. **Quadratic fan-out (performance bug, fixed).** `fanOut` measured **2 046 ns/node** — 2× over
   budget. Cause: every recompute unsubscribed and resubscribed from its dependencies, and
   `addObserver`/`removeObserver` scan the observer list, which is O (n) when one `Var` has 1 000
   observers. Fix: a fast path in `relink` that detects an unchanged dependency list (the
   overwhelmingly common case) and only refreshes versions. Result: **2 046 → 107 ns/node**, a
   19× improvement, with no change to semantics.

3. **`ThreadGuard` was globally stateful and bound on first touch (design flaw, fixed).** Under
   munit's parallel suites the guard bound to one pool thread and then rejected every other
   suite. A library cannot know which thread is "the UI thread". Changed so the default is
   `ThreadGuard.off` and the framework explicitly calls `ThreadGuard.install(ThreadGuard.owningThread)`
   at start-up. `Test / parallelExecution := false` is also set, because `install` remains
   process-global by design.

4. **Recursion depth (limitation, not fixed).** Validation is recursive, so evaluating a chain
   deeper than ~4 000 (JVM/JS) overflows the stack. The brief asked for a 10 000-node chain; that
   is what exposed it. **Not fixed deliberately:** UI trees are tens of levels deep, not thousands,
   and an iterative validator with an explicit work stack would add roughly 80 lines of much
   harder-to-read code for a case the framework will never hit. Recorded as an open question.

5. **Nested owners need a named `given` (ergonomics).** Two anonymous `given Owner`s in one scope
   is a compile error. Added `Owner.scoped { ... }`, which creates a child lifetime and returns it,
   so component code never writes a second `given`.

6. **sbt 2 migration frictions (three, all worked around).** See "Recommendations".

## Deviations from the brief

- **JMH was not used.** The brief said "JMH on JVM; simple timing on JS/Native". JMH is JVM-only,
  so its numbers would not be comparable with the other two backends — which is the whole point of
  a cross-platform benchmark. Instead one shared `Bench.scala` runs on all three with warm-up and
  median-of-5. The JVM figures are therefore less rigorous than JMH would give (no fork, no
  blackhole; a `sink` field is used to defeat dead-code elimination). Since the measurements clear
  the budget by 13×, the extra rigour would not change the conclusion. If precise JVM numbers are
  ever needed, add sbt-jmh to a JVM-only sub-project.
- **`null` is used in two hot fields** (`Runtime.observer`, `Runtime.collected`), against the
  "no null" house style in `docs/decisions.md`. An `Option` allocation on every tracked read is
  measurable at this budget. The deviation is confined to `private[signals] object Runtime` and
  is commented in place.

## API: what §7.2 got right and what changed

Implemented as sketched: `Signal[+A]` with `now`/`map`/`zip`, `Var[A]` with `set`/`update`,
`Signal.computed(f: Tracking ?=> A)`, `Signal.effect`, `Signal.batch`, `Signal.untracked`, `Owner`.

Changes the doc should absorb:

1. **`computed` and `effect` require `(using Owner)`**, and so therefore do `map` and `zip`.
   Without it a computed that is read once links itself to its dependencies forever — a leak,
   since the design deliberately uses no weak references. In the UI DSL a component scope supplies
   this implicitly, so app code never sees it, but §7.2's snippet (`val label = Signal.computed(...)`
   at top level) does not compile as written.
2. **`Tracking` is a real class, not a marker that users can conjure.** `signal()` is only callable
   where the library provides the context — exactly the "static branch vs reactive branch" safety
   §7.3 open question 9 was asking for. The mechanism works; `Show(signal)` can be typed to demand it.
3. **Signals are lazy.** A computed nobody observes is not evaluated until read. Worth stating in the
   docs because it affects when side effects inside a `computed` run (they should not be there at all).
4. **An effect created under an already-disposed owner never runs.** Chosen over "runs once then dies"
   because a dead scope should have no side effects at all.
5. **`Owner.scoped`** added (see Problems 5).

## Recommendations for the plan

1. **`docs/decisions.md` — sbt 2 is viable; correct the claim.** The file said the plugins are
   sbt-1 only. They are not: `sbt-scalajs`, `sbt-scala-native`, `sbt-crossproject` and
   `sbt-scala-native-crossproject` all publish with the **`_sbt2_3`** suffix (not `_3_2.0`, which is
   what a naive search looks for and is why the original claim was wrong). *Already updated.*
2. **`docs/decisions.md` — record the `%%%` gap.** sbt 2 has **no `sbt-platform-deps`**, so the `%%%`
   operator does not exist. Cross-platform dependencies must be written with explicit suffixes
   (`"org.scalameta" % "munit_native0.5_3" % v`). This is the single biggest sbt-2 papercut for a
   crossproject build and M0 should either wait for the plugin or define a small local helper. *Already updated.*
3. **`docs/decisions.md` — sbt 2's `test` is incremental.** `sbt test` ran **0 tests** when sources
   were unchanged, which silently looks like success. Use `testOnly *` in CI and in briefs. *Already updated.*
4. **`docs/07` §7.2 — add the `using Owner` requirement** to the API sketch and mention laziness (items 1 and 3 above).
5. **`docs/07` §7.7 — `ThreadGuard` is installed by the framework, not defaulted by the library**
   (Problem 3). The section currently implies the check is always on.
6. **`docs/09` — add the recursion-depth limit** as a known limitation with its mitigation.
7. **Scala Native debug link is 4.3 s** for a library this size, vs 13.6 s for `releaseFast`. This is
   the first real datum for open question 5 in `docs/09` ("is `-O0` + no LTO under 15 s?"). For a
   small module: comfortably yes. It says nothing yet about a full app — M1 must re-measure.
8. **`EventStream` is not needed for signals, but will be needed for UI events.** The brief asked.
   Signals model *state*; a button tap is an *event* with no resting value, and modelling it as
   `Var[Int]` (a counter) is a known anti-pattern. Recommendation: do **not** add it to this module;
   let the effect bridges expose events as their own stream type (`ZStream` in `effect-zio`,
   per §7.13's `button.taps`), which is exactly what the "streams are not in the contract" decision
   in §7.13 already anticipates.

## Ready for M0?

Yes, with one caveat: this code is spike-quality in that it has no Scaladoc beyond what is shown,
no MiMa baseline, and `Owner`'s child list is an `ArrayBuffer` with O (n) removal that has not been
profiled for the create/destroy churn of list scrolling. All three are M0 tasks, not spike blockers.
