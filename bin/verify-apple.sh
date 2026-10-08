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
#   link           sbt nativeLink for the four Apple apps
#   macos-gallery  THICKET_GALLERY_SELFTEST=1 gallery-macos
#   macos-todo     THICKET_SELFTEST=1 todo-macos
#   ios-gallery    the gallery on the simulator (examples/gallery/ios/ios-app/build-app.sh)
#   ios-todo       the todo app on the simulator (examples/todo-apple/ios/ios-app/build-app.sh)
#   fit            ContentFit measured on both toolkits (shim/run-fit-harness.sh)
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

run toolchain sh -c 'sw_vers; xcodebuild -version; xcrun swiftc --version'
run shim-macos modules/renderer-apple/shim/build-shim.sh macos
run shim-ios modules/renderer-apple/shim/build-shim.sh ios-sim
run abi sbt --error shimGen/testFull
run link sbt --error galleryMacos/nativeLink todoMacos/nativeLink galleryIos/nativeLink todoIos/nativeLink
run macos-gallery macos_selftest "$(native gallery-macos)" THICKET_GALLERY_SELFTEST
run macos-todo macos_selftest "$(native todo-macos)" THICKET_SELFTEST
run ios-gallery examples/gallery/ios/ios-app/build-app.sh "$device"
run ios-todo examples/todo-apple/ios/ios-app/build-app.sh "$device"
run fit modules/renderer-apple/shim/run-fit-harness.sh "$out/fit" "$device"

# Keep what a reviewer would want to look at next to the logs.
cp examples/gallery/ios/ios-app/build/*.png "$out/" 2>/dev/null || true
cp examples/todo-apple/ios/ios-app/build/*.png "$out/" 2>/dev/null || true

{
  echo "Apple verification — $(git rev-parse --short HEAD) on $(sw_vers -productVersion), $device"
  printf '%s\n' "${results[@]}"
  if [ "$failed" -eq 0 ]; then echo "ALL STEPS PASSED"; else echo "$failed STEP(S) FAILED"; fi
} | tee "$out/summary.txt"

[ "$failed" -eq 0 ]
