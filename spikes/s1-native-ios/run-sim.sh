#!/usr/bin/env bash
# Build the Scala Native static library for the iOS simulator under a given GC, link the
# C harness against it, and run it inside a booted simulator.
#
#   ./run-sim.sh [gc] [stress_seconds] [device]
#
# Reports link time and .a size on stdout alongside the harness's own RESULT lines, so a
# single invocation produces every number S1's REPORT.md needs for one GC.
set -euo pipefail

GC="${1:-immix}"
STRESS="${2:-5}"
DEVICE="${3:-iPhone 17}"

cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/s1-$GC"
mkdir -p "$OUT"

# The sbt server captures S1_GC at startup, so a server left over from another GC would
# silently rebuild the previous one.
pkill -f "sbt-launch" 2>/dev/null || true
sleep 1

echo "=== [$GC] nativeLink ==="
rm -rf target/out/native0.5/scala-3.9.0/s1-simarm64
START=$(date +%s.%N)
S1_GC="$GC" sbt --error "simArm64/nativeLink" 2>&1 | tail -5
END=$(date +%s.%N)
LINK_S=$(echo "$END - $START" | bc)

A=target/out/native0.5/scala-3.9.0/s1-simarm64/libs1-simarm64.a
echo "RESULT gc=$GC link_seconds=$(printf '%.1f' "$LINK_S") archive_bytes=$(stat -f%z "$A")"

SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
clang -target arm64-apple-ios17.0-simulator -isysroot "$SDK" \
  harness/main.c "$A" -o "$OUT/harness"
echo "RESULT gc=$GC harness_bytes=$(stat -f%z "$OUT/harness")"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true

echo "=== [$GC] running harness, stress=${STRESS}s ==="
xcrun simctl spawn "$DEVICE" "$OUT/harness" "$STRESS"
