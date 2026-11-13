#!/usr/bin/env bash
# Compile thicket-camera-preview-apple's Swift library to a static library that the app's final
# link takes in (#148). One file for both targets: `#if os(iOS)` picks a UIView, otherwise an
# NSView, around the same AVCaptureSession. The object names AVFoundation, CoreMedia and UIKit or
# AppKit (Swift's autolink), so an app's link needs no framework flags for them.
#
#   ./build-shim.sh            # macOS          -> build/libthicketpreview.a
#   ./build-shim.sh ios-sim    # iOS simulator  -> build-ios/libthicketpreview.a
#
# Its own archive in its own jar, like thicket-camera-apple's: an app that shows no preview links
# none of it.
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-macos}"

case "$TARGET" in
  macos)
    OUT=build
    TRIPLE=arm64-apple-macosx13.0
    SDK=$(xcrun --sdk macosx --show-sdk-path)
    ;;
  ios-sim)
    OUT=build-ios
    TRIPLE=arm64-apple-ios17.0-simulator
    SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
    ;;
  *)
    echo "unknown target: $TARGET (want macos | ios-sim)" >&2
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
  -module-name ThicketCameraPreview \
  Sources/PreviewShim.swift \
  -o "$OUT/preview.o"

ar rcs "$OUT/libthicketpreview.a" "$OUT/preview.o"
echo "built $OUT/libthicketpreview.a for $TARGET ($(stat -f%z "$OUT/libthicketpreview.a") bytes)"
