# S5 — Cross-platform fine-grained signals — BRIEF

**Machine:** Linux. **Budget:** ~1 week. **Depends on:** nothing. **Gate:** no (design
warm-up), but its output becomes `scala-ui-signals` at M0, so quality matters.

## Question
Can a SolidJS/Preact-Signals-style reactive core (`Var`, `Signal`, `computed`,
`effect`, `batch`, ownership/disposal) be written in pure Scala 3, cross-compile to
JVM, Scala.js and Scala Native, be glitch-free, and be fast enough (§7.2)?

## Deliverables
1. `spikes/s5-signals/` sbt build with `sbt-crossproject` (JVM/JS/Native), Scala 3 LTS.
2. `signals/shared/src/main/scala/scalaui/signals/` implementing:
   - `Signal[+A]` (`now`, `map`, `zip`, `flatMap`? — decide and justify), `Var[A]`
     (`set`, `update`), `Signal.computed(f: Tracking ?=> A)` with automatic
     dependency tracking via the `Tracking` context, `Signal.effect(...)` returning
     `Disposable`, `Signal.batch { }`, `Owner` scopes (children disposed with parent),
     `Signal.untracked { }`.
   - Semantics: **synchronous, glitch-free** (a computed never observes an
     inconsistent pair of inputs; each computed re-evaluates at most once per batch),
     topological/height-based scheduling or push-pull with dirty marking — document
     the choice.
   - Single-threaded by contract: `set` off the owning thread throws
     `IllegalStateException` with a message (JVM/Native only; JS is trivially single-threaded).
     Provide a `ThreadGuard` hook so the framework can plug the UI thread check.
3. Tests (munit + scalacheck), run on all three backends:
   - diamond dependency (a → b, a → c, b,c → d): d evaluates once per change of a
   - dynamic dependencies (a computed that reads different vars on different runs)
   - disposal: effect stops firing after owner disposed; no leaks (weak refs not required; explicit graph unlinking)
   - batch coalescing
   - exceptions inside computed: graph stays consistent, error propagates to caller of `set`
   - property test: random graphs, random updates, computed values always equal pure recomputation
4. Benchmarks (JMH on JVM; simple timing on JS/Native): 10 000-node chain and
   1 000-wide fan-out; report ns per node update on each backend.
5. `REPORT.md`.

## Pass criteria
- All tests pass on JVM, JS, Native.
- Propagation cost ≤ 1 µs per node on Native and JVM (warm), ≤ 5 µs on JS. Report actual.
- No compiler plugin, no macros (`inline` is fine). No dependency other than the Scala stdlib.
- API sketch in `07` §7.2 is either implemented or the report says what to change and why.

## Stop conditions
- If glitch-freedom needs more than ~1 000 lines of core code, stop and report — we
  will consider a simpler push model.
- Do not add streams/event buses (Airstream-style `EventStream`); signals only.
  Note in the report whether an `EventStream` type seems necessary for UI events.

## Do not
- Depend on Airstream, Monix, ZIO, cats. This library must sit *under* the effect bridges.
- Implement UI elements or renderers.
