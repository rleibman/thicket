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
#
# Two modes:
#   bin/verify-getting-started.sh                        publishes this checkout locally, then
#                                                        builds against that
#   THICKET_VERSION=0.2.0 bin/verify-getting-started.sh  a release: nothing is published; the
#                                                        template resolves from Maven Central alone
# In the second, sbt gets an empty coursier cache, Ivy home and global base, so a jar left in
# ~/.ivy2/local or a global plugin's resolver cannot stand in for Central — and the script
# fails unless every thicket jar the build used was downloaded from repo1.maven.org. Without
# that check, a release version that was also `publishLocal`-ed here would pass unseen.
set -euo pipefail

cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ -n "${THICKET_VERSION:-}" ]; then
  ver="$THICKET_VERSION"
  echo "== a released version, $ver: resolving from Maven Central only =="
  export COURSIER_CACHE="$work/coursier"
  export SBT_OPTS="${SBT_OPTS:-} -Dsbt.ivy.home=$work/ivy -Dsbt.global.base=$work/sbt-global -Dsbt.boot.directory=$HOME/.sbt/boot"
else
  echo "== publishing the framework locally =="
  sbt --error \
    "signalsJVM/publishLocal; signalsNative/publishLocal" \
    "rendererApiJVM/publishLocal; rendererApiNative/publishLocal" \
    "coreJVM/publishLocal; coreNative/publishLocal" \
    "effectZioJVM/publishLocal; effectZioNative/publishLocal" \
    "rendererGtk/publishLocal" \
    "sbtThicket/publishLocal"

  vers=$(sbt --error "print coreJVM/version; print sbtThicket/version" | tr -d '\r' | sed $'s/\x1b\\[[0-9;]*[A-Za-z]//g' | grep -E '^[0-9]' | tail -2)
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
fi

cp -r templates/hello-thicket "$work/app"
cd "$work/app"

# THICKET_VERSION reaches both build.sbt and project/plugins.sbt: the sbt-thicket plugin is
# resolved by the meta-build, so a stale plugin version fails the *load*, before any of this.
echo "== building the template, outside the repo, against the published jars =="
THICKET_VERSION="$ver" sbt --error nativeLink

if [ -n "${THICKET_VERSION:-}" ]; then
  # Every thicket jar the build used, and where it came from. The four the template needs
  # must be there, and none may come from anywhere but Central.
  jars=$(find "$COURSIER_CACHE" -path '*/net/leibman/*' -name "*-$ver.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar')
  echo "$jars" | sed -E 's|.*/https/([^/]+)/.*/([^/]+)$|== resolved \2 from \1|'
  for m in sbt-thicket_sbt2_3 thicket-core_native0.5_3 thicket-renderer-gtk_native0.5_3 thicket-signals_native0.5_3; do
    echo "$jars" | grep -q "/https/repo1.maven.org/maven2/net/leibman/$m/$ver/" ||
      { echo "FAIL: $m $ver was not downloaded from Maven Central" >&2; exit 1; }
  done
  if echo "$jars" | grep -v '/https/repo1.maven.org/' | grep -q .; then
    echo "FAIL: a thicket jar came from somewhere other than Maven Central" >&2; exit 1
  fi
fi

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

echo "PASS: a new app outside the repo resolves, links and runs against $ver${THICKET_VERSION:+ from Maven Central}"
