#!/usr/bin/env bash
# Build the SwiftUI host app, link it against the Scala Native static library, and install
# it into a booted simulator.
#
#   ./build-app.sh [device]
#
# The bundle is assembled with swiftc + simctl rather than through an .xcodeproj so the
# whole thing is reproducible from a script. What it proves is the same thing an Xcode
# build would: Swift links the archive, the runtime starts inside a real app process, and
# the exported functions are callable from the UI thread and from a background queue.
set -euo pipefail

DEVICE="${1:-iPhone 17}"
cd "$(dirname "$0")"

A="../target/out/native0.5/scala-3.9.0/s1-simarm64/libs1-simarm64.a"
[ -f "$A" ] || { echo "Missing $A — run 'sbt simArm64/nativeLink' first" >&2; exit 1; }

SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
APP="build/S1App.app"
rm -rf build && mkdir -p "$APP"

echo "=== compiling Swift + linking $(basename "$A") ==="
swiftc \
  -target arm64-apple-ios17.0-simulator \
  -sdk "$SDK" \
  -parse-as-library \
  -import-objc-header Sources/ScalaUI-Bridging-Header.h \
  Sources/App.swift \
  "$A" \
  -o "$APP/S1App"

cp Info.plist "$APP/Info.plist"
echo "RESULT app_binary_bytes=$(stat -f%z "$APP/S1App")"

# The shipped size is the stripped one; the unstripped binary carries Scala Native's debug
# and symbol tables, which Xcode would strip for a release build.
cp "$APP/S1App" build/S1App.unstripped
strip -x "$APP/S1App" 2>/dev/null || true
echo "RESULT app_binary_stripped_bytes=$(stat -f%z "$APP/S1App")"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true

echo "=== installing and launching ==="
xcrun simctl install "$DEVICE" "$APP"
xcrun simctl launch --console-pty "$DEVICE" dev.scalaui.s1app &
LAUNCH_PID=$!
sleep 8
kill $LAUNCH_PID 2>/dev/null || true
echo "=== launched; use 'xcrun simctl io \"$DEVICE\" screenshot out.png' to capture ==="
