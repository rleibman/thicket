#!/usr/bin/env bash
# Build the UIKit host + Swift shim, link them against the Scala Native archive, and run
# the result in a simulator.
#
#   ./build-app.sh [device]
#
# One swiftc invocation compiles both the shim and the host: the shim's @_cdecl functions
# satisfy the archive's undefined sui_* symbols, and the archive's exports satisfy the
# host's calls to ScalaNativeInit/scalaui_main. That mutual resolution is the thing S3 is
# checking, so it is worth seeing it happen in one link step.
set -euo pipefail

DEVICE="${1:-iPhone 17}"
cd "$(dirname "$0")"

A="../target/out/native0.5/scala-3.9.0/s3-scala-lib/libs3-scala-lib.a"
[ -f "$A" ] || { echo "Missing $A — run 'sbt scalaLib/nativeLink' first" >&2; exit 1; }

SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
APP="build/S3App.app"
rm -rf build && mkdir -p "$APP"

echo "=== compiling shim + host, linking $(basename "$A") ==="
swiftc \
  -target arm64-apple-ios17.0-simulator \
  -sdk "$SDK" \
  -parse-as-library \
  -import-objc-header Sources/ScalaUI-Bridging-Header.h \
  -Xcc -I../shim/include \
  ../shim/Sources/Shim.swift \
  Sources/Host.swift \
  "$A" \
  -o "$APP/S3App"

cp Info.plist "$APP/Info.plist"
echo "RESULT app_unstripped_bytes=$(stat -f%z "$APP/S3App")"
cp "$APP/S3App" build/S3App.unstripped
strip -x "$APP/S3App" 2>/dev/null || true
echo "RESULT app_stripped_bytes=$(stat -f%z "$APP/S3App")"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true
xcrun simctl terminate "$DEVICE" dev.scalaui.s3app 2>/dev/null || true
xcrun simctl install "$DEVICE" "$APP"
xcrun simctl launch "$DEVICE" dev.scalaui.s3app
