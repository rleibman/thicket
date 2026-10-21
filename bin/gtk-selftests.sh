#!/usr/bin/env bash
# Builds and runs both GTK self-tests, and fails unless each passed cleanly.
#
# "Cleanly" is more than the verdict line. A self-test exits with its failure count, but GTK
# reports some faults only on stderr — a `Gtk-CRITICAL` from a widget used after it was freed
# arrived *after* "ALL CHECKS PASSED" in one run of four, and nothing failed (review
# 2026-10-20, P2-20). So the output is checked as well.
#
# Needs a display: under CI, run it inside `xvfb-run -a` (or a headless weston).
set -euo pipefail
cd "$(dirname "$0")/.."

# One argument, `;`-separated: the sbt launcher CI installs joins separate arguments with a
# space, which sbt then cannot parse as two commands.
sbt --error "galleryGtk/nativeLink; counterGtk/nativeLink"
sv=$(sbt --error "print galleryGtk/scalaVersion" 2>/dev/null | tr -d '\r' | tail -1)
out="target/out/native0.5/scala-$sv"
logs=target/gtk-selftests
mkdir -p "$logs"

status=0
run() { # name, env var, binary
  local name=$1 var=$2 bin=$3 code=0
  echo "== $name =="
  env "$var=1" timeout 300 "$bin" >"$logs/$name.log" 2>&1 || code=$?
  local verdict
  verdict=$(grep -E '(ALL CHECKS PASSED|CHECK\(S\) FAILED)' "$logs/$name.log" | tail -1 || true)
  local faults
  faults=$(grep -cE 'Gtk-CRITICAL|GLib-CRITICAL|signal 11|uncaught exception' "$logs/$name.log" || true)
  echo "   exit $code; ${verdict:-no verdict}; $faults fault line(s); $(grep -cE ' ok ' "$logs/$name.log") checks passed"
  if [ "$code" -ne 0 ] || [ "$faults" -ne 0 ] || ! grep -q 'ALL CHECKS PASSED' <<<"$verdict"; then
    grep -E 'FAIL|CRITICAL|signal 11|uncaught' "$logs/$name.log" | head -20 || true
    status=1
  fi
}

run gallery THICKET_GALLERY_SELFTEST "$out/gallery-gtk/gallery-gtk"
run counter THICKET_SELFTEST "$out/counter-gtk/counter-gtk"
exit $status
