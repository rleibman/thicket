#!/usr/bin/env bash
# Compile the Apple shim to a static library that Scala Native links into the final binary.
#
#   ./build-shim.sh            # macOS / AppKit  -> build/libscalauiapple.a
#   ./build-shim.sh ios-sim    # iOS simulator / UIKit -> build-ios/libscalauiapple.a
#
# Two Swift files, one header, one Scala renderer. S3 recommended per-*file* separation
# rather than `#if` inside function bodies, because AppKit and UIKit diverge structurally:
# NSView's coordinate system is flipped relative to UIKit's, NSButton uses target/action
# with different enums, and there is no NSControl.Event. Only one of the two is ever
# compiled, chosen here.
#
# Built outside sbt because sbt 2 cannot put a File on a classpath, and because Scala
# Native's own clang invocation knows nothing about Swift.
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-macos}"

case "$TARGET" in
  macos)
    SRC=Sources/Shim+AppKit.swift
    OUT=build
    TRIPLE=arm64-apple-macosx13.0
    SDK=$(xcrun --sdk macosx --show-sdk-path)
    ;;
  ios-sim)
    SRC=Sources/Shim+UIKit.swift
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
  -module-name ScalaUIApple \
  -import-objc-header include/scalaui_apple_types.h \
  "$SRC" \
  -o "$OUT/shim.o"

ar rcs "$OUT/libscalauiapple.a" "$OUT/shim.o"
echo "built $OUT/libscalauiapple.a for $TARGET ($(stat -f%z "$OUT/libscalauiapple.a") bytes)"
