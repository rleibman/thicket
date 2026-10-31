#!/usr/bin/env bash
# Compile thicket-motion-ios's Swift library to a static library that the app's final link takes
# in (#120). iOS only: CMMotionManager is unavailable on macOS, which uses Motion.unavailable.
# The object names CoreMotion (Swift's autolink), so an app's link needs no framework flag for it.
#
#   ./build-shim.sh            # iOS simulator  -> build-ios/libthicketmotion.a
#   ./build-shim.sh ios-sim    # the same
#
# Its own archive in its own jar, like thicket-device-apple's: an app that does not use motion
# links none of it, nor CoreMotion.
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-ios-sim}"

case "$TARGET" in
  ios-sim)
    OUT=build-ios
    TRIPLE=arm64-apple-ios17.0-simulator
    SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
    ;;
  *)
    echo "unknown target: $TARGET (want ios-sim; macOS has no CMMotionManager)" >&2
    exit 1
    ;;
esac

rm -rf "$OUT" && mkdir -p "$OUT"

swiftc \
  -emit-object \
  -O \
  -parse-as-library \
  -target "$TRIPLE" \
  -sdk "$SDK" \
  -wmo \
  -module-name ThicketMotion \
  Sources/MotionShim.swift \
  -o "$OUT/motion.o"

ar rcs "$OUT/libthicketmotion.a" "$OUT/motion.o"
echo "built $OUT/libthicketmotion.a for $TARGET ($(stat -f%z "$OUT/libthicketmotion.a") bytes)"
