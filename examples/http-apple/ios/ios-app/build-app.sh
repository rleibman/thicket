#!/usr/bin/env bash
# Link the HTTP self-test's iOS host against thicket-http-apple's Swift library and the Scala
# Native archive, and run it on a simulator against the server on <port>.
#
#   ./build-app.sh <device> <port>        normally run by examples/http-apple/selftest.sh ios
#
# Prerequisites:
#   modules/http-apple/shim/build-shim.sh ios-sim   (sbt does this when it packages httpApple)
#   sbt httpIos/nativeLink
#
# Note what is not linked: modules/renderer-apple's libthicketapple.a. HTTP needs no renderer.
set -euo pipefail

DEVICE="${1:-iPhone 17}"
PORT="${2:?usage: build-app.sh <device> <port>}"
BUNDLE_ID="dev.thicket.httpios"
cd "$(dirname "$0")"

REPO=$(cd ../../../.. && pwd)
A="$REPO/target/out/native0.5/scala-3.9.0/http-ios/libhttp-ios.a"
LIB="$REPO/modules/http-apple/shim/build-ios/libthickethttp.a"

[ -f "$LIB" ] || { echo "Missing $LIB — run modules/http-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
[ -f "$A" ]   || { echo "Missing $A — run 'sbt httpIos/nativeLink'" >&2; exit 1; }

SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
APP="build/HttpIos.app"
rm -rf build && mkdir -p "$APP"

echo "=== linking host + libthickethttp.a + $(basename "$A") ==="
swiftc \
  -target arm64-apple-ios17.0-simulator \
  -sdk "$SDK" \
  -parse-as-library \
  -import-objc-header Sources/Thicket-Bridging-Header.h \
  Sources/Host.swift \
  "$LIB" "$A" \
  -o "$APP/HttpIos"

cp Info.plist "$APP/Info.plist"
echo "RESULT app_unstripped_bytes=$(stat -f%z "$APP/HttpIos")"
strip -x "$APP/HttpIos" 2>/dev/null || true
echo "RESULT app_stripped_bytes=$(stat -f%z "$APP/HttpIos")"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$DEVICE" "$APP"

# --console-pty, backgrounded, as the todo host does: a simulator app's stdout reaches only a
# pty. SIMCTL_CHILD_ passes the port through to the app's environment.
LOG=build/run.log
SIMCTL_CHILD_THICKET_HTTPTEST="$PORT" xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" >"$LOG" 2>&1 &
LAUNCHER=$!
for _ in $(seq 1 60); do
  grep -q "ALL CHECKS PASSED\|CHECK(S) FAILED" "$LOG" 2>/dev/null && break
  sleep 1
done

kill "$LAUNCHER" 2>/dev/null || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true

echo "=== app output ==="
cat "$LOG"
echo "=== end ==="
grep -q "ALL CHECKS PASSED" "$LOG"
