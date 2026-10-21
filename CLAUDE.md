# Thicket — instructions for agents working in this repository

Thicket is a Scala 3 UI framework: one element tree, rendered with each platform's own
widgets — GTK 4 on Linux, Android views, AppKit and UIKit. Phase 0 (the spikes) is done and
archived; the framework is in `modules/`, the apps that exercise it in `examples/`. Work comes
as GitHub issues or as a request in the prompt. Do that, verify it, and stop.

## Read in this order (10 minutes)

1. `README.md` — what exists, how to build it, the commands below in context.
2. `docs/decisions.md` — versions, tools, naming. The decision log at the bottom is newest
   first and overrides the tables above it.
3. `docs/12-component-status.md` — what each widget does on each platform, and the gallery rule.
4. Only as needed: `docs/07-technical-design-ideas.md` (the design), `docs/14-releasing.md`
   (versions, publishing), `docs/09-open-questions.md`, `docs/05-requirements.md` (IDs like
   N-01). 01–06, 08 and 10 are the plan and phase 0's findings: background.

## Building and checking (Linux)

| What | Command |
|---|---|
| Every test, format, headers, every renderer and example compiled | `sbt --error checkLinux` (what CI runs) |
| Tests only | `sbt --error testFull` — not `test`, which is incremental and can run nothing |
| Format | `sbt scalafmtAll` — reaches the renderers and examples too |
| GTK self-tests (gallery + todo), gated on exit code and stderr | `bin/gtk-selftests.sh` |
| Android gallery / todo on the `s2test` emulator | `SELFTEST=1 examples/gallery/android/build.sh`, `SELFTEST=1 examples/todo-android/build.sh` |
| A new app outside the repo against published jars | `bin/verify-getting-started.sh` |

The renderers and examples are aggregated by `platforms`, not `root`, so a fresh clone's
`testFull` needs no `android.jar`; run `modules/renderer-android/setup.sh` once before
compiling them. The heap is in `.jvmopts`.

## Standing rules

- **Scope.** Do what was asked. Findings outside it go in the PR description or an issue,
  not in the change.
- **Git.** Never `git commit`, `git push`, `git tag` unless the user asks in that
  message. Never commit to `main`. Check `git rev-parse --abbrev-ref HEAD` first.
- **Run `sbt scalafmtAll` before every commit.** Format, do not merely check —
  otherwise the next person's diff is full of reflows that have nothing to do with
  their change.
- **Never merge a PR. Ever.** Not via `gh`, not via an API, not by fast-forwarding the base
  branch locally, and not because the PR was approved or you were asked to "finish" the
  work. Open it, say it is ready, stop. Merging is the user's, with no exception.
- **Apple work is the Mac's.** Anything that needs Xcode — the Swift shims, linking or
  running an Apple app — is done by the owner's Mac session. When a Linux change has an Apple
  half, write it up as a GitHub issue for the Mac rather than guessing at the Swift. The
  Apple Scala does type-check here (`sbt platforms/Test/compile`).
- **sbt.** Always `sbt --error ...`; drop `--error` only when you need the extra output.
- **sbt-mcp**, when it is running. `.mcp.json` registers an MCP server, `sbt-mcp-thicket`,
  that a *long-lived* `sbt` session hosts on `127.0.0.1:5010`. When it is up, prefer it over
  shelling out: `sbt-task` to run commands (`;`-separated), `list-tasks` to discover them,
  `check` for a fast compile-free type check of edited files, and `glob-search` / `inspect` /
  `symbol-location` for Scala symbol questions in preference to text search or guessing an
  API.
  Two caveats worth knowing before relying on it. It exists only while that session is
  loaded — a one-shot `sbt foo` starts a server and takes it away again — so when it is
  absent, say so and use the CLI rather than pretending. And only one session can hold the
  port: while a persistent one is up, every other `sbt` invocation logs
  `sbt-mcp: failed to start MCP server: Address already in use` and runs without one. That
  line is noise, not a build failure, and it has already been mistaken for one in an error
  count.
- **System changes.** Ask before installing system packages, SDKs or Xcode components, or
  anything outside the repo. Repo-local tooling (`cs install`, sbt plugins) is fine.
- **Docs** (decision log, 2026-10-20, D2):
  - **Frozen:** 01–08 — the plan as it was written. Do not edit them.
  - **Append-only:** `docs/decisions.md` (a dated entry at the top of the decision log;
    a table row it supersedes may be struck through with a pointer to the entry), `09`, `10`.
  - **Living:** 11–15, `docs/phase-reviews/README.md`, and every README. Keep them true in
    the same change that makes them false — a count, a status, a "not built yet".
- **Every new component goes in `examples/gallery`, in the same change.** That project is
  one screen with every component, built for every platform. `TestRenderer` answers for
  every widget, so a widget that works on GTK and does nothing on Android passes the whole
  test suite — the gallery and its self-tests are what catch it. Conditions in
  `examples/gallery/README.md`; rule in `docs/12-component-status.md` §12.10.
- **Measure, don't assert.** A claim that something works comes with the number and the
  command that produced it — in the commit message and the PR. Where a check can pass
  without the fix, break the fix and show the check fail.
- **Report honestly.** Say what was not done, what failed, and what was not verified. A
  clear root cause for a failure is a result.
- **Verify versions.** Resolve latest versions with the commands in
  `docs/decisions.md` rather than from memory; record what you used.

## Machines

| Machine | OS | Use for |
|---|---|---|
| This box | Linux (Ubuntu), clang 21, sbt, coursier, gradle, node 22; Android SDK at `~/Android/Sdk` with the `s2test` AVD (API 36); JDK 21 via `cs java-home --jvm temurin:21` | everything but Apple: core, signals, GTK, Android, CI |
| Laptop | macOS + Xcode | the Apple renderer and apps; `bin/verify-apple.sh`; the self-hosted runner for `.github/workflows/apple.yml` |
| Windows box | Windows 10/11 | later (Win32/WinUI) |

The user is an Android user. The iOS **simulator** is the primary Apple target; a physical
device is a bonus measurement.

## History

`spikes/` holds phase 0's briefs and reports; the spike code is at commit `8eccd90`.
`docs/phase-reviews/` holds whole-repository reviews, dated.
