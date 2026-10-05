# 14. Releasing: versions, compatibility and what a consumer needs

## 14.1 Versions come from git tags

`GitVersioning` owns `version`. Nothing in `build.sbt` sets it, and an explicit
`ThisBuild / version := ...` is **silently overridden** — a hard-coded `"0.1.0-SNAPSHOT"` sat
on that line for a while and never reached a single artefact.

| What git says | What the artefact is called |
|---|---|
| on tag `v0.2.0` | `0.2.0` |
| three commits past `v0.2.0` | `0.2.0-3-gdeadbee` |
| no tag reachable | `0.1.0-<sha>-SNAPSHOT` (`git.baseVersion` + sha) |

Tags are `v`-prefixed; `gitTagToVersionNumber` strips the `v` and ignores anything that is
not a version, so a tag like `demo-recording` does not become one.

**There are no tags yet**, which is why every artefact currently carries a sha. That is
correct for a snapshot and useless as something to type in a `libraryDependencies` line, so
the first real release is a `v0.1.0` tag and nothing else.

## 14.2 `early-semver`

`versionScheme := Some("early-semver")`, declared so coursier and sbt can tell an eviction
from a breaking change rather than inferring it from the number.

Before 1.0, that means: **`0.x.y` → `0.x.z` is binary compatible, `0.x` → `0.y` need not be.**
After 1.0 it means the usual thing. The scheme is a promise to tooling, so it is worth being
honest about which line is which rather than picking the number that sounds better.

What counts as breaking here is wider than it looks, because of two deliberate design
choices:

- **`Prop` is an exhaustive `enum` under `-Werror`.** Adding a case breaks every renderer
  that has not handled it — including one outside this repo. A new prop is a **minor** bump
  before 1.0 and a **major** one after.
- **`Renderer` is a trait an app may implement.** Adding an abstract member to it is the
  same kind of break. `WidgetKind` is the same shape of hazard.

Neither is an accident — that pressure is what stops a widget silently doing nothing on one
platform — but it does mean "we only added something" is not automatically compatible.

## 14.3 What a consumer actually needs

The artefacts, with explicit suffixes, because sbt 2 has no `%%%`:

| Artefact | For |
|---|---|
| `dev.thicket:thicket-core_3` / `_native0.5_3` | the element tree, signals, navigation |
| `dev.thicket:thicket-renderer-gtk_native0.5_3` | the GTK4 renderer |
| `dev.thicket:thicket-effect-zio_3` / `_native0.5_3` | the ZIO bridge, if wanted |

`thicket-signals` and `thicket-renderer-api` come in transitively; naming them is only
necessary to write a renderer.

### The part that is not published, and should be

A GTK app also needs about a dozen lines of `nativeConfig` — the `pkg-config` compile and
link flags, the GC, the LTO and the mode — and **nothing publishes them**. Today a consumer
copies them out of `templates/hello-thicket/build.sbt`, which works and is ugly: it couples
every app to a build detail of ours, and an app that gets the GC wrong fails at link time
with nothing pointing at the cause.

The fix is an `sbt-thicket` plugin exposing `thicketGtkSettings`. It is not in phase 5's
scope and is tracked separately; the template is the honest interim answer rather than a
pretence that there is nothing missing.

## 14.4 Checking the getting-started still works

```bash
./bin/verify-getting-started.sh
```

Publishes the framework locally, copies `templates/hello-thicket` **outside** the repo, and
builds and runs it against the published artefacts. Outside is the point: building the
template in place would resolve the modules as project dependencies and prove nothing about
what a stranger gets from a jar.

A GUI app does not exit on its own, so staying up is the pass — the script treats a 10s
timeout as success and any other exit, or a `signal 11`, `CRITICAL` or theme-parser line, as
failure.

Phase 5's exit criterion is a person getting an app running in under thirty minutes. Only a
person can measure that; this checks the half that does not need one.
