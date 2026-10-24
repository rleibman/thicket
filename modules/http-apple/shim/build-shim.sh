#!/usr/bin/env bash
# Compile thicket-http-apple's Swift library to a static library that the app's final link
# takes in (#90). The same files for both targets: they use Foundation only. Idn2.swift
# supplies the three libidn2 functions sttp-model calls, which neither SDK has.
#
#   ./build-shim.sh            # macOS          -> build/libthickethttp.a
#   ./build-shim.sh ios-sim    # iOS simulator  -> build-ios/libthickethttp.a
#
# Separate from modules/renderer-apple/shim on purpose: an app that does not use HTTP must
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
  -module-name ThicketHttp \
  Sources/HttpShim.swift Sources/Idn2.swift \
  -o "$OUT/http.o"

ar rcs "$OUT/libthickethttp.a" "$OUT/http.o"
echo "built $OUT/libthickethttp.a for $TARGET ($(stat -f%z "$OUT/libthickethttp.a") bytes)"
