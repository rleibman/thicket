#!/usr/bin/env bash
# Answer the system alert on a simulator: "Don't Allow" (deny) or "Allow" (allow) (#114).
#
#   examples/ios-host/tapper/tap.sh <device> deny|allow
#
# Runs TapperUITests, an XCUITest with no target application, which taps SpringBoard's button.
# The first run builds it (into build/, about 20 s); later runs reuse the build and take ~8 s,
# most of it xcodebuild starting the runner. Exit status is the test's; a failure fails in about
# 25 s (no button), without the sysdiagnose xcodebuild otherwise collects for up to 10 min.
set -euo pipefail
DEVICE="${1:?device}"
WHAT="${2:?deny|allow}"
cd "$(dirname "$0")"

# xcodebuild wants an id or an unambiguous name; take the booted device of that name.
UDID=$(xcrun simctl list devices booted | grep -F "    $DEVICE (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
[ -n "$UDID" ] || { echo "tap.sh: no booted simulator named '$DEVICE'" >&2; exit 1; }

mkdir -p build
action=test-without-building
[ -d build/Build/Products ] || action=test
TEST_RUNNER_TAP="$WHAT" xcodebuild "$action" -project Tapper.xcodeproj -scheme TapperUITests \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath build \
  -collect-test-diagnostics never >build/tap.log 2>&1 || {
  grep -E '\[tapper\]|error|failed' build/tap.log | tail -5
  exit 1
}
grep '\[tapper\]' build/tap.log
