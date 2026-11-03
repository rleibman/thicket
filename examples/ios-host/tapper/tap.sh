#!/usr/bin/env bash
# Answer system UI on a simulator, as a user would (#114, #125, #134).
#
#   examples/ios-host/tapper/tap.sh <device> deny|allow
#   examples/ios-host/tapper/tap.sh <device> photo|cancel|export <bundle id>
#   examples/ios-host/tapper/tap.sh <device> document <bundle id> <file name>
#   examples/ios-host/tapper/tap.sh <device> '<bundle id>|<label>'
#
# Runs TapperUITests, an XCUITest with no target application: deny/allow tap SpringBoard's
# alert; photo, cancel, document and export tap the picker the app with <bundle id> shows (#125);
# '<bundle id>|<label>' brings that app to the front and taps the cell so labelled (the share
# sheet's "Copy", #134). See TapperUITests.swift.
# The first run builds it (into build/, about 20 s); later runs reuse the build and take ~8 s,
# most of it xcodebuild starting the runner. Exit status is the tap's: 0 once the test has tapped,
# 1 when it found nothing to tap (about 25 s), without the sysdiagnose xcodebuild otherwise collects
# for up to 10 min, and without XCTest's tear-down (below).
set -euo pipefail
DEVICE="${1:?device}"
WHAT="${2:?deny|allow|photo|cancel|document|export|<bundle id>|<label>}"
APP_ID="${3:-}"
FILE_NAME="${4:-}"
cd "$(dirname "$0")"

# xcodebuild wants an id or an unambiguous name; take the booted device of that name.
UDID=$(xcrun simctl list devices booted | grep -F "    $DEVICE (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
[ -n "$UDID" ] || { echo "tap.sh: no booted simulator named '$DEVICE'" >&2; exit 1; }

mkdir -p build
action=test-without-building
[ -d build/Build/Products ] || action=test
# Built again when the test has changed since: an older build lacks the newer actions (#125, #134).
[ -z "$(find TapperUITests -newer build/Build/Products -name '*.swift' 2>/dev/null)" ] || action=test
: >build/tap.log
TEST_RUNNER_TAP="$WHAT" TEST_RUNNER_APP="$APP_ID" TEST_RUNNER_FILE="$FILE_NAME" \
  xcodebuild "$action" -project Tapper.xcodeproj -scheme TapperUITests \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build \
  -collect-test-diagnostics never >build/tap.log 2>&1 &
xb=$!
# The test's own line, or its assertion, ends the wait; then xcodebuild is stopped. XCTest's tear-down
# took 300-308 s after the tap on Xcode 27 with the iOS 27 simulator (measured 2026-11-02, three runs,
# #134: "Tear Down" with nothing under it), and a self-test waiting on that misses its own timeouts.
# The tap is done by then; nothing after it matters. (The picker actions also end the test with
# exit(0) once tapped, #125; either way the "[tapper] tapped" line is the verdict.)
result=""
while kill -0 "$xb" 2>/dev/null; do
  if grep -q '\[tapper\] tapped' build/tap.log; then result=ok; break; fi
  if grep -q 'error: -\[TapperUITests' build/tap.log; then result=failed; break; fi
  sleep 0.5
done
if [ -n "$result" ]; then kill "$xb" 2>/dev/null || true; fi
wait "$xb" 2>/dev/null || true
if [ "$result" = ok ] || { [ -z "$result" ] && grep -q '\[tapper\] tapped' build/tap.log; }; then
  grep '\[tapper\]' build/tap.log
else
  grep -E '\[tapper\]|error|failed' build/tap.log | tail -5
  exit 1
fi
