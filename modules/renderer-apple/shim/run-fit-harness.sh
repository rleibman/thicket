#!/usr/bin/env bash
# Measure ContentFit on both Apple toolkits (Forgejo #6).
#
#   ./run-fit-harness.sh [outdir] [device]
#
# Renders a 400x100 test image into a 200x200 frame under Contain, Cover and Fill, then
# measures the drawn result rather than asserting on the property that was set: a fit that
# distorts is invisible in a log and obvious in a bitmap.
#
# The harness links the shim sources directly, so it exercises the same @_cdecl entry points
# the app calls — sui_create, sui_set_image_file, sui_set_content_fit, sui_set_frame — and
# not a Swift-only reimplementation of them.
#
# The UIKit half runs headless under `simctl spawn`, so it needs no app bundle and no
# UIScene; it is a confirmation rather than a fix, because .scaleAspectFill is already Cover.
set -euo pipefail
cd "$(dirname "$0")"

OUT="${1:-build/fit}"
DEVICE="${2:-iPhone 17}"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

echo "=== AppKit ==="
xcrun swiftc -O \
  -target arm64-apple-macosx13.0 -sdk "$(xcrun --sdk macosx --show-sdk-path)" \
  -module-name FitHarness -import-objc-header include/scalaui_apple_types.h \
  Sources/Shim+AppKit.swift Tests/FitHarness+AppKit.swift -o "$OUT/fitharness-macos"
"$OUT/fitharness-macos" "$OUT"

echo
echo "=== UIKit (simulator, headless) ==="
# The sysroot warning is swiftc's ClangImporter not inheriting -sdk for -Xcc; cosmetic, as
# the bridging header is types-only. Same note as ios-app/build-app.sh.
xcrun swiftc -O \
  -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
  -module-name FitHarness -import-objc-header include/scalaui_apple_types.h \
  Sources/Shim+UIKit.swift Tests/FitHarness+UIKit.swift -o "$OUT/fitharness-ios" 2>&1 \
  | grep -v "Wincompatible-sysroot" || true
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true
xcrun simctl spawn "$DEVICE" "$OUT/fitharness-ios" "$OUT"
