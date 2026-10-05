#!/usr/bin/env bash
# Does the getting-started actually work?
#
# Publishes the framework locally, copies `templates/hello-thicket` somewhere outside this
# repo, and builds and runs it against the published artefacts. That last part is the point:
# building the template *inside* the repo would resolve the modules as project dependencies
# and prove nothing about what a stranger gets from a published jar.
#
# Phase 5's exit criterion is a person getting an app running in under thirty minutes, which
# only a person can measure. This checks the part that does not need one: that the artefacts
# resolve, link and run from outside.
set -euo pipefail

cd "$(dirname "$0")/.."
root=$(pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

echo "== publishing the framework locally =="
sbt --error \
  "signalsJVM/publishLocal; signalsNative/publishLocal" \
  "rendererApiJVM/publishLocal; rendererApiNative/publishLocal" \
  "coreJVM/publishLocal; coreNative/publishLocal" \
  "effectZioJVM/publishLocal; effectZioNative/publishLocal" \
  "rendererGtk/publishLocal" \
  "sbtThicket/publishLocal"

vers=$(sbt --error "print coreJVM/version" "print sbtThicket/version" | tr -d '\r' | tail -2)
ver=$(echo "$vers" | head -1)
pluginver=$(echo "$vers" | tail -1)
[ -n "$ver" ] || { echo "could not read the version" >&2; exit 1; }
# The plugin puts its *own* version on the thicket dependencies it adds, so if it is not the
# framework's version the template resolves artefacts nobody published. One build produces
# both, so they agree; checked rather than assumed, because the failure is a resolution error
# in someone else's project.
[ "$ver" = "$pluginver" ] || {
  echo "FAIL: framework version $ver but sbt-thicket version $pluginver" >&2; exit 1; }
echo "== version $ver =="

cp -r templates/hello-thicket "$work/app"
cd "$work/app"

# THICKET_VERSION reaches both build.sbt and project/plugins.sbt: the sbt-thicket plugin is
# resolved by the meta-build, so a stale plugin version fails the *load*, before any of this.
echo "== building the template, outside the repo, against the published jars =="
THICKET_VERSION="$ver" sbt --error nativeLink

bin=$(find . -type f -name hello-thicket -perm -u+x | head -1)
[ -n "$bin" ] || { echo "no binary produced" >&2; exit 1; }
echo "== binary: $(stat -c%s "$bin") bytes =="

# A GUI app does not exit on its own, so staying up *is* the pass. 124 is the timeout's
# way of saying "still running", and anything else means it fell over.
echo "== running it for 10s =="
set +e
timeout 10 "$bin" >"$work/run.log" 2>&1
code=$?
set -e

if grep -qiE "signal 11|CRITICAL|Theme parser" "$work/run.log"; then
  echo "FAIL: the app reported a problem:" >&2
  cat "$work/run.log" >&2
  exit 1
fi

if [ "$code" -ne 124 ]; then
  echo "FAIL: expected the app to still be running after 10s, got exit $code" >&2
  cat "$work/run.log" >&2
  exit 1
fi

echo "PASS: a new app outside the repo resolves, links and runs against $ver"
