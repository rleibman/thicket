# 13. Phases, branches and PRs

How the work is cut up from here to 0.1. One phase → one branch → one PR. Living document:
a phase's row changes when it opens and when it merges.

**Why phases and not a backlog.** Every phase below is chosen so that the repository is
*runnable and demonstrable* at its end, and so that the next phase is cheaper because of it.
That is the same rule `docs/11` §11.5 used, and it is the reason the renderer contract has
survived four toolkits without changing.

## 13.1 The rules

1. **One phase, one branch, one PR.** Branch name `phase/<n>-<slug>`, cut from `main`.
   Never commit to `main` directly; the user merges the PR.
2. **A phase is done when its exit criterion is a measured number**, recorded in the PR
   description and in `docs/12`. "Works" fails the phase — the same rule phase 0's spikes
   used, and the reason phase 0's findings are trustworthy.
3. **Apple work is raised as a Forgejo issue, never attempted on Linux.** A phase may
   *depend* on an Apple issue; it may not contain one. Phases are sized so that a blocked
   Apple issue never blocks the whole phase.
4. **A widget is done on every renderer that exists, or it is not done.** Adding a
   `WidgetKind` breaks every renderer's exhaustive match under `-Werror`, which is the
   design working: it makes falling behind impossible to do quietly.
5. **`docs/12` is updated in the same PR as the code.** A status doc updated afterwards is a
   status doc that is wrong for a while.

## 13.2 The phases

| Phase | Branch | Scope | Exit criterion (a number) | Status |
|---|---|---|---|---|
| **0** | — | Feasibility: nine spikes, four renderers, reconciliation, navigation, ZIO bridge | GO gate S1 ∧ S2 ∧ S3 | **done** 2026-09-19 |
| **1** | `phase/1-shim-generator` | One ABI description; generate the C header, the Scala externs and Swift signature scaffolding; check all four hand-written copies agree | Hand-maintained **declarations** per function drop from **4 to 1**; the generated header and externs declare exactly what the checked-in ones do; the consistency check runs on a machine with no Xcode | **code done, not yet adopted** |
| **2** | `phase/2-widget-breadth` | **Slice 1:** `Toggle`, `Spacer`, `ProgressBar`, `ActivityIndicator`. **Slice 2:** `Slider`, `SecureField`. `Radio` and `Stepper` were in this phase and were **reclassified, not built** — neither is a cross-platform widget (`docs/12` §12.2a). `IconButton` and `Link` need an icon system and URL opening | **15 of 32** on GTK + Android, each with a unit test *and* a self-test check on both; Apple is #9. Measured on both toolkits: progress bar **0.750**, slider **7.0 of 0–11** | **both slices done** |
| **3** | `phase/3-containers` | `Alert`, `Sheet`/`Modal`, `TabView`, `Menu`, `Toolbar` — the ones that are platform *chrome* rather than tree nodes | **22 of 32**; each rendered by the platform's own presentation API, not imitated in the tree; a screenshot per platform | |
| **4** | `phase/4-native-nav` | Native navigation containers and per-subtree `Provide` theming — the two places the framework still asks an app to accept something non-native | Back gesture, transition animation and title bar are the platform's own on all four renderers; theme override scoped to a subtree with a test | |
| **5** | `phase/5-release` | Publish `dev.thicket` artefacts, a getting-started, semver policy | A developer who has not seen this repo builds and runs a new app on Android **and** one desktop in **≤ 30 minutes**, measured by watching them | |
| **6** | `phase/6-real-app` | The falsification test: the meal-planner client against its Caliban server | Runs on Android, iOS and one desktop; startup and size still inside N-01/N-03 | |

**Out of scope for 0.1**, each with nothing above it that depends on it: Windows
(Win32/WinUI), Yoga / `FrameBased` layout, the Scala.js dev canvas, the `thicket` CLI, the
inspector, and the cats-effect bridge.

## 13.3 Why phase 1 is first — and what measuring it changed

It is not the most visible work, and it is still first. But the first thing phase 1 did was
disprove its own original framing, which is worth recording rather than quietly editing.

**The plan was "generate the shim".** The number behind that was 11.0 non-comment Swift
lines per exported function across two shims, projecting ~5 200 lines of duplicated Swift
for the full catalogue. Measured properly, per shim:

| | AppKit | UIKit |
|---|---|---|
| Exported functions | 34 | 35 |
| Signature lines — generatable | 80 | 82 |
| Body lines — hand-written | 211 | 212 |

Of the 34 functions in both shims, **7** have byte-identical bodies, 13 differ only in a
type or property name, and **14 are genuinely different code**. The bodies are real AppKit
and UIKit logic: `NSImageView.imageScaling` is not `UIView.contentMode`,
`placeholderString` is not `placeholder`, AppKit's coordinate system is flipped. **A
generator that emitted bodies would be emitting guesses**, and the projected 5 200-line
saving was never available.

**What is available, and is worth more.** Every boundary function is declared *four* times
— C header, two `@_cdecl` signatures, one Scala `extern`. At 34 functions that is **136
declarations kept in step by hand, with nothing checking them**: Swift compiles against its
own signature, Scala Native against its own `extern`, and the linker matches them **by name
only**. An `Int32` against an `Int64` links cleanly and reads a garbage register at runtime,
in a callback, on a phone.

So phase 1's real deliverable is a described ABI plus the check that the four copies agree
— and that check runs on a machine with no Xcode, which is exactly where the mistake gets
made. Generation of the declaration layer falls out of the description for free.

Phase 1 is still first: it is what stops the remaining 23 widgets multiplying 4 undetected
declarations each, and the catalogue is only 9 of 32.

## 13.4 Open Apple issues

Tracked in Forgejo, done on the macOS laptop, referenced by the phase that needs them.

| Issue | What | Blocks |
|---|---|---|
| ~~[#4](https://forgejo.leibmanland.com/rleibman/scala-ui/issues/4)~~ | ~~Honour `Prop.Axis` — horizontal `Scroll`~~ — **done**, both shims | phase 2 (Apple parity) |
| [#5](https://forgejo.leibmanland.com/rleibman/scala-ui/issues/5) | A virtualising container, so `LazyColumn` stops mounting every row | phase 6 (a real app has real lists) |
| [#6](https://forgejo.leibmanland.com/rleibman/scala-ui/issues/6) | `ContentFit.Cover` distorts instead of cropping on AppKit | phase 2 |
| [#7](https://forgejo.leibmanland.com/rleibman/scala-ui/issues/7) | Generate shim, header and bindings from one description | **phase 1** |

## 13.5 On the estimates

There are deliberately no dates here. `docs/08` budgeted phase 0 at 6–8 weeks; it took
roughly one working day across two machines, and its own recalibration note says the
milestone figures were produced by the same reasoning and should be treated as equally
inflated.

The phases are ordered and sized relative to one another. They are not scheduled. Steer by
working software and re-estimate from the actual slope.
