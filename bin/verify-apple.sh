#!/usr/bin/env bash
# Build both Apple shims and run every Apple self-test, on a Mac with Xcode (#45, #46).
#
#   bin/verify-apple.sh [device]          device defaults to 'iPhone 17'
#
# The Swift in modules/renderer-apple/shim is otherwise only ever *written* on Linux: nothing
# there can compile it, and #49 shipped a call to a helper that did not exist because of it.
# This is the one command that compiles it and then asks the real toolkits what they drew.
#
# Every step runs even when an earlier one fails — one run should show everything that is
# broken, not the first thing — and the summary at the end is the result. Exit status is
# non-zero if any step failed. Logs, the simulator screenshots and the summary are left in
# target/apple-verify/ (CI uploads that directory).
#
# Steps, in order:
#   toolchain      xcrun swiftc / xcodebuild versions, recorded with the result
#   shim-macos     compile Shim+AppKit.swift (build-shim.sh macos)
#   shim-ios       compile Shim+UIKit.swift  (build-shim.sh ios-sim)
#   abi            shim-gen's consistency checks: ABI, C header, Scala externs, both shims
#   link           sbt nativeLink for the four Apple apps, after deleting their old outputs
#   macos-gallery  THICKET_GALLERY_SELFTEST=1 gallery-macos
#   macos-todo     THICKET_SELFTEST=1 todo-macos
#   macos-lazy     THICKET_LAZYTEST=1 todo-macos: 10 000 rows, virtualised and freed
#   ios-gallery    the gallery on the simulator (examples/gallery/ios/ios-app/build-app.sh)
#   ios-todo       the todo app on the simulator (examples/todo-apple/ios/ios-app/build-app.sh)
#   ios-lazy       the 10 000-row probe on the simulator
#   fit            ContentFit measured on both toolkits (shim/run-fit-harness.sh)
#   getting-started  bin/verify-getting-started-apple.sh: publish locally, build and run the
#                  macOS and iOS templates from outside the repository
#
# Every iOS step starts from a freshly booted simulator: a SIMCTL_CHILD_ variable keeps its
# first value for the whole boot session, so without a reboot one app's mode leaks into the
# next launch (docs/decisions.md, #22).
#
# The macOS self-tests open real windows, so this must run in a logged-in GUI session — for
# the CI runner that means a LaunchAgent, not a LaunchDaemon. See .github/workflows/apple.yml.
set -uo pipefail

[ "$(uname -s)" = Darwin ] || { echo "bin/verify-apple.sh needs macOS with Xcode" >&2; exit 2; }

cd "$(dirname "$0")/.."
root=$(pwd)
device="${1:-iPhone 17}"
out="$root/target/apple-verify"
rm -rf "$out" && mkdir -p "$out"

results=()
failed=0

# Scala Native links need more than sbt's default 1 GB heap; a caller's own setting wins.
: "${SBT_OPTS:=-Xms1g -Xmx4g}"
export SBT_OPTS

# run <name> <command...>: run a step, log it to $out/<name>.log, record PASS/FAIL and time.
run() {
  local name=$1; shift
  local log="$out/$name.log" start end status
  echo "== $name =="
  start=$(date +%s)
  if "$@" >"$log" 2>&1; then status=PASS; else status=FAIL; failed=$((failed + 1)); fi
  end=$(date +%s)
  results+=("$(printf '%-14s %-4s %4ss' "$name" "$status" $((end - start)))")
  if [ "$status" = FAIL ]; then
    echo "   FAIL — last lines of $log:"
    tail -n 25 "$log" | sed 's/^/   | /'
  fi
}

# A macOS self-test: start the app with the switch set, wait for its verdict, then stop it.
# The apps stay open after reporting (as on GTK), so the verdict line is what ends the wait —
# a crash or a hang ends it too, and fails.
macos_selftest() {
  local bin=$1 var=$2 log
  log=$(mktemp)
  [ -x "$bin" ] || { echo "missing $bin — the link step failed"; return 1; }
  env "$var=1" "$bin" >"$log" 2>&1 &
  local pid=$!
  for _ in $(seq 1 90); do
    grep -q "ALL CHECKS PASSED\|CHECK(S) FAILED" "$log" && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  cat "$log"
  grep -q "ALL CHECKS PASSED" "$log"
}

native() { find "$root/target/out" -type f -perm -u+x -name "$1" 2>/dev/null | head -1; }

# The four apps' outputs are deleted before linking, so a self-test can only ever run what
# this run built: a failed link then fails the self-tests too, instead of letting them pass on
# a binary from an earlier build — which the first run of this script did.
link_all() {
  find "$root/target/out" \( -name gallery-macos -o -name todo-macos -o -name 'libgallery-ios.a' \
    -o -name 'libtodo-ios.a' \) -type f -delete 2>/dev/null || true
  local t ok=0
  # One command per invocation: sbt's thin client does not take several as separate
  # arguments, and rejected all four at once in the first run.
  for t in galleryMacos todoMacos galleryIos todoIos; do
    echo "-- $t/nativeLink"
    sbt --error "$t/nativeLink" || ok=1
  done
  return $ok
}

# A freshly booted simulator, then the step.
on_fresh_simulator() {
  xcrun simctl shutdown "$device" >/dev/null 2>&1 || true
  xcrun simctl boot "$device" || return 1
  xcrun simctl bootstatus "$device" -b >/dev/null 2>&1 || true
  "$@"
}

ios_lazy() { SIMCTL_CHILD_THICKET_LAZYTEST=1 examples/todo-apple/ios/ios-app/build-app.sh "$device"; }

# A running sbt server keeps the build as it was when it loaded — including the version,
# which comes from `git describe` — across branch switches. Measured: a run by hand reported
# another commit's version. So this run starts its own.
sbt shutdown >/dev/null 2>&1 || true

run toolchain sh -c 'sw_vers; xcodebuild -version; xcrun swiftc --version'
run shim-macos modules/renderer-apple/shim/build-shim.sh macos
run shim-ios modules/renderer-apple/shim/build-shim.sh ios-sim
run abi sbt --error shimGen/testFull
run link link_all
run macos-gallery macos_selftest "$(native gallery-macos)" THICKET_GALLERY_SELFTEST
run macos-todo macos_selftest "$(native todo-macos)" THICKET_SELFTEST
run macos-lazy macos_selftest "$(native todo-macos)" THICKET_LAZYTEST
run ios-gallery on_fresh_simulator examples/gallery/ios/ios-app/build-app.sh "$device"
run ios-todo on_fresh_simulator examples/todo-apple/ios/ios-app/build-app.sh "$device"
run ios-lazy on_fresh_simulator ios_lazy
run fit modules/renderer-apple/shim/run-fit-harness.sh "$out/fit" "$device"
run getting-started bin/verify-getting-started-apple.sh "$device"

# Keep what a reviewer would want to look at next to the logs.
cp examples/gallery/ios/ios-app/build/*.png "$out/" 2>/dev/null || true
cp examples/todo-apple/ios/ios-app/build/*.png "$out/" 2>/dev/null || true

{
  echo "Apple verification — $(git rev-parse --short HEAD) on $(sw_vers -productVersion), $device"
  printf '%s\n' "${results[@]}"
  if [ "$failed" -eq 0 ]; then echo "ALL STEPS PASSED"; else echo "$failed STEP(S) FAILED"; fi
} | tee "$out/summary.txt"

[ "$failed" -eq 0 ]
