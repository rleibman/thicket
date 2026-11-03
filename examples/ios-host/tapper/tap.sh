#!/usr/bin/env bash
# Answer the system alert on a simulator: "Don't Allow" (deny) or "Allow" (allow) (#114).
#
#   examples/ios-host/tapper/tap.sh <device> deny|allow
#   examples/ios-host/tapper/tap.sh <device> "app <bundle id> <label>|<label>…"   (#129)
#
# Runs TapperUITests, an XCUITest with no target application, which taps SpringBoard's button.
# The first run builds it (into build/, about 20 s); later runs reuse the build and take ~8 s,
# most of it xcodebuild starting the runner. Exit status is the test's; a failure fails in about
# 25 s (no button), without the sysdiagnose xcodebuild otherwise collects for up to 10 min.
set -euo pipefail
DEVICE="${1:?device}"
WHAT="${2:?deny|allow|app <bundle id> <labels>}"
cd "$(dirname "$0")"

# xcodebuild wants an id or an unambiguous name; take the booted device of that name.
UDID=$(xcrun simctl list devices booted | grep -F "    $DEVICE (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
[ -n "$UDID" ] || { echo "tap.sh: no booted simulator named '$DEVICE'" >&2; exit 1; }

mkdir -p build
action=test-without-building
[ -d build/Build/Products ] || action=test
# Built again when the test changed since (a stale build would not know a newer TAP form).
if [ -n "$(find TapperUITests -newer build/Build/Products 2>/dev/null)" ]; then action=test; fi
TEST_RUNNER_TAP="$WHAT" xcodebuild "$action" -project Tapper.xcodeproj -scheme TapperUITests \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build \
  -collect-test-diagnostics never >build/tap.log 2>&1 &
XCB=$!
# Measured on Xcode 27 (#129): the test taps in about 3 s, then its Tear Down takes 300 s, with
# or without another simulator busy, which held every later DRIVE line back and timed out the
# checks waiting on them. The test prints "[tapper] done" once every tap is made (or
# "[tapper] failed"); that is the answer, so xcodebuild is stopped there, by its PID.
status=
while kill -0 "$XCB" 2>/dev/null; do
  if grep -q '\[tapper\] done' build/tap.log 2>/dev/null; then status=0; break; fi
  if grep -q '\[tapper\] failed' build/tap.log 2>/dev/null; then status=1; break; fi
  sleep 0.5
done
if [ -n "$status" ]; then
  kill "$XCB" 2>/dev/null || true
  wait "$XCB" 2>/dev/null || true
else
  if wait "$XCB"; then status=0; else status=1; fi
fi
if [ "$action" = test ] && [ "$status" = 0 ]; then touch build/Build/Products; fi
if [ "$status" != 0 ]; then
  grep -E '\[tapper\]|error|failed' build/tap.log | tail -5
  exit 1
fi
grep '\[tapper\]' build/tap.log
