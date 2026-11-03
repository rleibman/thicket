#!/usr/bin/env bash
# Compile thicket-pickers-apple's Swift library to a static library that the app's final link
# takes in (#125). One file for both targets: `#if os(iOS)` picks PHPickerViewController and
# UIDocumentPickerViewController, or AppKit's NSOpenPanel and NSSavePanel. The object names
# PhotosUI, UIKit or AppKit and UniformTypeIdentifiers (Swift's autolink), so an app's link needs
# no framework flags for them.
#
#   ./build-shim.sh            # macOS          -> build/libthicketpickers.a
#   ./build-shim.sh ios-sim    # iOS simulator  -> build-ios/libthicketpickers.a
#
# Separate from modules/renderer-apple/shim on purpose: an app that does not use it must
# not link any of it, so it is its own archive in its own jar.
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
  -module-name ThicketPickers \
  Sources/PickersShim.swift \
  -o "$OUT/pickers.o"

ar rcs "$OUT/libthicketpickers.a" "$OUT/pickers.o"
echo "built $OUT/libthicketpickers.a for $TARGET ($(stat -f%z "$OUT/libthicketpickers.a") bytes)"
