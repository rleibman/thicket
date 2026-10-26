#!/usr/bin/env bash
# Compile the Apple shim to a static library that Scala Native links into the final binary.
#
#   ./build-shim.sh            # macOS / AppKit  -> build/libthicketapple.a
#   ./build-shim.sh ios-sim    # iOS simulator / UIKit -> build-ios/libthicketapple.a
#
# Two Swift files, one header, one Scala renderer. S3 recommended per-*file* separation
# rather than `#if` inside function bodies, because AppKit and UIKit diverge structurally:
# NSView's coordinate system is flipped relative to UIKit's, NSButton uses target/action
# with different enums, and there is no NSControl.Event. Only one of the two is ever
# compiled, chosen here.
#
# Three Swift files, in fact: Shim+Common.swift, the half that knows no toolkit, is compiled
# with whichever toolkit file is chosen, as one module (-wmo, so one object) (#79).
#
# **arm64 only.** Both slices are built for arm64 alone — an Apple-silicon Mac, and the
# simulator on one. An Intel Mac, or an x86_64 simulator, cannot link this library (and the
# iOS Scala Native archive is arm64 too: ThicketNativeFlags.ios's default triple). Adding
# x86_64 would mean a second swiftc per slice and `lipo`; nothing here runs on Intel to test
# it, so it is documented rather than done (#79).
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
  -wmo \
  -parse-as-library \
  -target "$TRIPLE" \
  -sdk "$SDK" \
  -module-name ThicketApple \
  -import-objc-header include/thicket_apple_types.h \
  Sources/Shim+Common.swift "$SRC" \
  -o "$OUT/shim.o"

ar rcs "$OUT/libthicketapple.a" "$OUT/shim.o"
echo "built $OUT/libthicketapple.a for $TARGET ($(stat -f%z "$OUT/libthicketapple.a") bytes)"
