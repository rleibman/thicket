#!/usr/bin/env bash
# Build the app and run it on the iOS simulator.
#
#   ios-app/build-app.sh [device]        e.g. ios-app/build-app.sh 'iPhone 17'
#
# Three pieces meet here, matched by symbol name at link time:
#   - the Scala half, a static library from `sbt nativeLink` (ThicketIosPlugin);
#   - Thicket's Swift shim, unpacked by `sbt thicketAppleShim` from the published
#     thicket-renderer-apple jar (`sbt 'print thicketAppleShim'` says where);
#   - the Swift host in Sources/, which owns `main`.
# One swiftc invocation compiles the host and links all three.
#
# Exits 0 if the app is still running after 10 seconds, which for a GUI app is the success
# condition, and non-zero if anything failed to build or the app fell over.
set -euo pipefail

DEVICE="${1:-iPhone 17}"
BUNDLE_ID="dev.thicket.hello"
cd "$(dirname "$0")/.."
ROOT=$(pwd)

echo "=== Scala half, and the Swift shim ==="
# `print` rather than a guessed path: sbt 2 keeps a project's target under target/out/..., so
# the task is asked where it unpacked to.
# Escapes stripped and the path matched anywhere on the line, for the same reason as the
# version in bin/verify-getting-started-apple.sh: under a CI runner an escape precedes it.
SHIM_DIR=$(sbt --error "print thicketAppleShim" | tr -d '\r' | sed $'s/\x1b\\[[0-9;]*[A-Za-z]//g' |
  grep -oE '/[^[:space:]]*thicket-apple' | tail -1 || true)
[ -n "$SHIM_DIR" ] || { echo "FAIL: sbt did not say where it unpacked the Swift shim" >&2; exit 1; }
sbt --error nativeLink

A=$(find "$ROOT/target" -type f -name "libhello-thicket-ios.a" | head -1)
SHIM="$SHIM_DIR/ios-sim/libthicketapple.a"
INCLUDE="$SHIM_DIR/include"
[ -n "$A" ] && [ -f "$A" ] || { echo "no static library from nativeLink" >&2; exit 1; }
[ -f "$SHIM" ] || { echo "no Swift shim at $SHIM" >&2; exit 1; }

cd ios-app
SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
APP="build/HelloThicket.app"
rm -rf build && mkdir -p "$APP"

# swiftc's ClangImporter may warn about the macOS sysroot when targeting the simulator. It is
# cosmetic: the bridging header is types-only, and the link uses the simulator SDK.
echo "=== linking the host, the shim and $(basename "$A") ==="
swiftc \
  -target arm64-apple-ios17.0-simulator \
  -sdk "$SDK" \
  -parse-as-library \
  -import-objc-header Sources/Thicket-Bridging-Header.h \
  -Xcc -I"$INCLUDE" \
  Sources/Host.swift \
  "$SHIM" "$A" \
  -o "$APP/HelloThicket"
cp Info.plist "$APP/Info.plist"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$DEVICE" "$APP"
PID=$(xcrun simctl launch "$DEVICE" "$BUNDLE_ID" | awk '{print $NF}')
echo "=== launched, pid $PID; waiting 10s ==="
sleep 10

# Captured first and then searched: under `set -o pipefail`, `launchctl list | grep -q` fails
# whenever grep finds the line early, because launchctl then dies of SIGPIPE.
running=$(xcrun simctl spawn "$DEVICE" launchctl list)
if grep -qE "^$PID[[:space:]].*UIKitApplication:$BUNDLE_ID" <<<"$running"; then
  xcrun simctl io "$DEVICE" screenshot build/HelloThicket.png >/dev/null 2>&1 || true
  echo "running after 10s"
else
  echo "FAIL: the app is not running after 10s" >&2
  exit 1
fi
