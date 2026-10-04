#!/usr/bin/env bash
# Build the gallery's UIKit host, link it against the Apple shim and the Scala Native archive, and
# run the result on a simulator.
#
#   ./build-app.sh [device]        e.g. ./build-app.sh 'iPhone 17'
#
# Prerequisites, in order:
#   modules/renderer-apple/shim/build-shim.sh ios-sim
#   sbt galleryIos/nativeLink
#
# One swiftc invocation compiles the host and resolves both directions: the shim's @_cdecl
# functions satisfy the archive's undefined sui_* symbols, and the archive's @exported
# symbols satisfy the host's calls.
set -euo pipefail

DEVICE="${1:-iPhone 17}"
BUNDLE_ID="dev.thicket.galleryios"
cd "$(dirname "$0")"

REPO=$(cd ../../../.. && pwd)
A="$REPO/target/out/native0.5/scala-3.9.0/gallery-ios/libgallery-ios.a"
SHIM="$REPO/modules/renderer-apple/shim/build-ios/libthicketapple.a"

[ -f "$SHIM" ] || { echo "Missing $SHIM — run modules/renderer-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
[ -f "$A" ]    || { echo "Missing $A — run 'sbt galleryIos/nativeLink'" >&2; exit 1; }

SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
APP="build/GalleryIos.app"
rm -rf build && mkdir -p "$APP"

# clang warns "using sysroot for 'macOS 27.0' but targeting arm64-apple-ios17.0-simulator"
# here. It comes from swiftc's ClangImporter, which does not inherit -sdk for the -Xcc
# flags; passing -Xcc -isysroot explicitly does not silence it. Cosmetic: the bridging
# header is types-only, nothing platform-dependent is imported through it, and the link
# itself uses the simulator SDK.
echo "=== linking host + shim + $(basename "$A") ==="
swiftc \
  -target arm64-apple-ios17.0-simulator \
  -sdk "$SDK" \
  -parse-as-library \
  -import-objc-header Sources/Thicket-Bridging-Header.h \
  -Xcc -I"$REPO/modules/renderer-apple/shim/include" \
  Sources/Host.swift \
  "$SHIM" "$A" \
  -o "$APP/GalleryIos"

cp Info.plist "$APP/Info.plist"
echo "RESULT app_unstripped_bytes=$(stat -f%z "$APP/GalleryIos")"
cp "$APP/GalleryIos" build/GalleryIos.unstripped
strip -x "$APP/GalleryIos" 2>/dev/null || true
echo "RESULT app_stripped_bytes=$(stat -f%z "$APP/GalleryIos")"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$DEVICE" "$APP"
# SIMCTL_CHILD_ passes an environment variable through to the app, which is how the
# self-test is switched on: the same THICKET_GALLERY_SELFTEST the macOS example reads.
#
# --console-pty, backgrounded: a simulator app's stdout does not reach os_log, so
# `log show` cannot see println output — only a pty can. It never returns on its own, so
# it is run detached, given time to finish, and then stopped.
LOG=build/run.log
SIMCTL_CHILD_THICKET_GALLERY_SELFTEST=1 xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" >"$LOG" 2>&1 &
LAUNCHER=$!
for _ in $(seq 1 30); do
  grep -q "ALL CHECKS PASSED\|CHECK(S) FAILED" "$LOG" 2>/dev/null && break
  sleep 1
done

xcrun simctl io "$DEVICE" screenshot build/GalleryIos.png >/dev/null 2>&1 || true
kill "$LAUNCHER" 2>/dev/null || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true

echo "=== app output ==="
cat "$LOG"
echo "=== end ==="
grep -q "ALL CHECKS PASSED" "$LOG"
