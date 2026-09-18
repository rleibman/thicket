# scala-ui — instructions for agents working in this repository

This repository is **phase 0 of a plan**: a Scala 3 UI framework for Android, iOS,
macOS, Windows and Linux. Nothing is built yet. Your job is almost certainly **one
spike**, named in the prompt that started you. Do that spike, write its report, stop.

## Read in this order (10 minutes)

1. `docs/decisions.md` — versions, tools, naming, layout. Binding.
2. `spikes/<your spike>/BRIEF.md` — your task, deliverables, pass criteria, stop conditions.
3. `docs/08-roadmap-and-spikes.md` §8.1 — how your spike fits.
4. Only as needed: `docs/07-technical-design-ideas.md` (design you are validating),
   `docs/04-scala-toolchain-reality.md` (known risks), `docs/05-requirements.md` (IDs like N-01).
   Do not read 01–03, 06 unless asked; they are background.

## Standing rules

- **Scope.** One spike per session. Do not start another spike, do not start
  framework code under `modules/`, do not "improve" other spikes. If your spike is
  blocked, write what you found in `REPORT.md` and stop.
- **Git.** Never `git commit`, `git push`, `git tag` unless the user asks in that
  message. Never commit to `main`. Check `git rev-parse --abbrev-ref HEAD` first.
- **sbt.** Always `sbt --error ...`; drop `--error` only when you need the extra output.
- **System changes.** Ask before installing system packages, SDKs (Android SDK,
  Xcode components), or anything outside the repo. Repo-local tooling
  (`cs install`, sbt plugins, npm in a spike dir) is fine.
- **Docs are frozen except:** append dated entries to `docs/decisions.md`
  ("Decision log") and to `docs/09-open-questions.md` when you learn something that
  changes the plan. Do not rewrite existing text.
- **Measure, don't assert.** Every pass criterion in a brief is a number or an
  observable; put the number and how you got it in `REPORT.md`. "Works" without a
  measurement fails the spike.
- **Report honestly.** A failed spike with a clear root cause is a *successful*
  spike. Do not soften or hide failures; they are the point of phase 0.
- **Verify versions.** Resolve latest versions with the commands in
  `docs/decisions.md` rather than from memory; record what you used.

## Machines

| Machine | OS | Use for |
|---|---|---|
| This box | Linux (Ubuntu), clang 21, sbt, scala-cli, coursier, gradle, node 22, **no Android SDK yet** | S5, S2 (after SDK install — ask), S4, S7, M0 |
| Laptop | macOS + Xcode | S1, S3, S8 (iOS simulator first; device if available), macOS renderer |
| Windows box | Windows 10/11 | Later (Win32/WinUI); nothing in phase 0 |

The user is an Android user; treat the iOS **simulator** as the primary S1 target
and a physical device as a bonus measurement.

## Spike report format (`spikes/<id>/REPORT.md`)

```
# S<n> — <title> — REPORT
Date, machine, versions used
## Result: PASS | PASS-WITH-RISK | FAIL
## Measurements (table: criterion → target → measured → how)
## What was built (paths)
## Problems hit and how solved / not solved
## Recommendations for the plan (concrete: "change X in doc Y")
```
