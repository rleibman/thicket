#!/usr/bin/env bash
# Answer system UI on a simulator, as a user would (#114, #125).
#
#   examples/ios-host/tapper/tap.sh <device> deny|allow
#   examples/ios-host/tapper/tap.sh <device> photo|cancel|export <bundle id>
#   examples/ios-host/tapper/tap.sh <device> document <bundle id> <file name>
#
# Runs TapperUITests, an XCUITest with no target application: deny/allow tap SpringBoard's
# alert; the others tap the picker the app with <bundle id> shows (see TapperUITests.swift).
# The first run builds it (into build/, about 20 s); later runs reuse the build and take ~8 s,
# most of it xcodebuild starting the runner. A failure fails in about 25 s, without the
# sysdiagnose xcodebuild otherwise collects for up to 10 min.
#
# The app actions end the test with exit(0) once tapped, since a UI test that touched an app
# takes 300 s to tear down (measured, #125), and xcodebuild then reports the test as failed: so
# success is the "[tapper] tapped" line, not xcodebuild's exit status.
set -euo pipefail
DEVICE="${1:?device}"
WHAT="${2:?deny|allow|photo|cancel|document|export}"
APP_ID="${3:-}"
FILE_NAME="${4:-}"
cd "$(dirname "$0")"

# xcodebuild wants an id or an unambiguous name; take the booted device of that name.
UDID=$(xcrun simctl list devices booted | grep -F "    $DEVICE (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
[ -n "$UDID" ] || { echo "tap.sh: no booted simulator named '$DEVICE'" >&2; exit 1; }

mkdir -p build
action=test-without-building
# Rebuilt when the test is newer than the last build (a stale runner lacks new actions).
[ -d build/Build/Products ] && [ build/built.stamp -nt TapperUITests/TapperUITests.swift ] || action=test
touch build/built.stamp
status=0
TEST_RUNNER_TAP="$WHAT" TEST_RUNNER_APP="$APP_ID" TEST_RUNNER_FILE="$FILE_NAME" \
  xcodebuild "$action" -project Tapper.xcodeproj -scheme TapperUITests \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build \
  -collect-test-diagnostics never >build/tap.log 2>&1 || status=$?
if grep -q '\[tapper\] tapped' build/tap.log; then
  grep '\[tapper\]' build/tap.log
  exit 0
fi
grep -E '\[tapper\]|error|failed' build/tap.log | tail -5
exit $((status == 0 ? 1 : status))
