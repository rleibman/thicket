#!/usr/bin/env bash
# Compile the AppKit shim to a static library that Scala Native links into the final binary.
#
# Built separately from sbt because sbt 2 cannot put a File on a classpath and because the
# shim is Swift, which Scala Native's own clang invocation knows nothing about.
set -euo pipefail
cd "$(dirname "$0")"

OUT=build
rm -rf "$OUT" && mkdir -p "$OUT"

swiftc \
  -emit-object \
  -O \
  -parse-as-library \
  -module-name ScalaUIAppKit \
  -import-objc-header include/scalaui_appkit_types.h \
  Sources/Shim.swift \
  -o "$OUT/shim.o"

ar rcs "$OUT/libscalauiappkit.a" "$OUT/shim.o"
echo "built $OUT/libscalauiappkit.a ($(stat -f%z "$OUT/libscalauiappkit.a") bytes)"
