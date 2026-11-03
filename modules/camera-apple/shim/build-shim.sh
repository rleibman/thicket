#!/usr/bin/env bash
# Compile thicket-camera-apple's Swift library to a static library that the app's final link takes
# in (#129). iOS only: UIImagePickerController is UIKit, and macOS uses Camera.unavailable.
# The object names UIKit (Swift's autolink), so an app's link needs no framework flag for it.
#
#   ./build-shim.sh            # iOS simulator  -> build-ios/libthicketcamera.a
#   ./build-shim.sh ios-sim    # the same
#
# Its own archive in its own jar, like thicket-motion-ios's: an app that does not take photos
# links none of it.
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
    echo "unknown target: $TARGET (want ios-sim; macOS has no camera screen, decision 7)" >&2
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
  -module-name ThicketCamera \
  Sources/CameraShim.swift \
  -o "$OUT/camera.o"

ar rcs "$OUT/libthicketcamera.a" "$OUT/camera.o"
echo "built $OUT/libthicketcamera.a for $TARGET ($(stat -f%z "$OUT/libthicketcamera.a") bytes)"
