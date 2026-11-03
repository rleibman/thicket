#!/usr/bin/env bash
# Answer system UI on a simulator, as a user would (#114, #125, #129, #134).
#
#   examples/ios-host/tapper/tap.sh <device> deny|allow
#   examples/ios-host/tapper/tap.sh <device> photo|cancel|export <bundle id>   (#125)
#   examples/ios-host/tapper/tap.sh <device> document <bundle id> <file name>   (#125)
#   examples/ios-host/tapper/tap.sh <device> "app <bundle id> <label>|<label>…"   (#129)
#   examples/ios-host/tapper/tap.sh <device> '<bundle id>|<label>'   (#134)
#
# Runs TapperUITests, an XCUITest with no target application, which taps SpringBoard's button;
# given photo, cancel, document or export, the picker the app with <bundle id> shows (#125);
# given "app …", that app's buttons in turn (the camera screen's, #129); given '<bundle id>|<label>',
# brings that app to the front and taps the cell so labelled (the share sheet's "Copy", #134).
# The first run builds it (into build/, about 20 s); later runs reuse the build and take ~8 s,
# most of it xcodebuild starting the runner. Exit status is the taps': 0 once the test has tapped,
# 1 when it found nothing to tap (about 25 s), without the sysdiagnose xcodebuild otherwise collects
# for up to 10 min, and without XCTest's tear-down (below).
set -euo pipefail
DEVICE="${1:?device}"
WHAT="${2:?deny|allow|photo|cancel|document|export|app <bundle id> <labels>|<bundle id>|<label>}"
APP_ID="${3:-}"
FILE_NAME="${4:-}"
cd "$(dirname "$0")"

# xcodebuild wants an id or an unambiguous name; take the booted device of that name.
UDID=$(xcrun simctl list devices booted | grep -F "    $DEVICE (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
[ -n "$UDID" ] || { echo "tap.sh: no booted simulator named '$DEVICE'" >&2; exit 1; }

mkdir -p build
action=test-without-building
[ -d build/Build/Products ] || action=test
# Built again when the test has changed since: an older build lacks the newer TAP forms (#125, #129, #134).
[ -z "$(find TapperUITests -newer build/Build/Products -name '*.swift' 2>/dev/null)" ] || action=test
: >build/tap.log
TEST_RUNNER_TAP="$WHAT" TEST_RUNNER_APP="$APP_ID" TEST_RUNNER_FILE="$FILE_NAME" \
  xcodebuild "$action" -project Tapper.xcodeproj -scheme TapperUITests \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build \
  -collect-test-diagnostics never >build/tap.log 2>&1 &
xb=$!
# XCTest's tear-down took 300-308 s after the taps on Xcode 27 with the iOS 27 simulator (measured
# 2026-11-02 in #129 and #134, with or without another simulator busy), which held every later
# DRIVE line back and timed out the checks waiting on them. The test prints "[tapper] done" once
# every tap is made, or "[tapper] failed"; that is the answer, so xcodebuild is stopped there.
result=""
while kill -0 "$xb" 2>/dev/null; do
  if grep -q '\[tapper\] done' build/tap.log; then result=ok; break; fi
  if grep -q '\[tapper\] failed\|error: -\[TapperUITests' build/tap.log; then result=failed; break; fi
  sleep 0.5
done
if [ -n "$result" ]; then kill "$xb" 2>/dev/null || true; fi
wait "$xb" 2>/dev/null || true
if [ "$result" = ok ] || { [ -z "$result" ] && grep -q '\[tapper\] done' build/tap.log; }; then
  if [ "$action" = test ]; then touch build/Build/Products; fi
  grep '\[tapper\]' build/tap.log
else
  grep -E '\[tapper\]|error|failed' build/tap.log | tail -5
  exit 1
fi
