#!/usr/bin/env bash
# Uniform measurement for every S6 option, so the numbers compare rather than each
# framework self-reporting.
#
#   ./measure.sh <bundle-id> <app-bundle-path> [device] [runs]
#
# Cold start: the host records a Unix epoch timestamp immediately before `simctl launch`,
# and each app prints `S6_FIRST_RENDER <epoch>` the moment its first UI update is on
# screen. Both clocks are the host's, because simulator apps are host processes. An
# earlier version polled screenshots instead; `simctl io screenshot` costs ~420 ms, which
# is coarser than the thing being measured.
#
# RSS: read from the host with ps, for the same reason.
set -uo pipefail

BUNDLE_ID="$1"
APP_PATH="$2"
DEVICE="${3:-iPhone 17}"
RUNS="${4:-3}"
TMP="${TMPDIR:-/tmp}/s6measure"
mkdir -p "$TMP"

echo "RESULT bundle=$BUNDLE_ID"
echo "RESULT app_bundle_bytes=$(du -sk "$APP_PATH" | awk '{print $1*1024}')"
EXE=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP_PATH/Info.plist" 2>/dev/null)
[ -f "$APP_PATH/$EXE" ] && echo "RESULT main_binary_bytes=$(stat -f%z "$APP_PATH/$EXE")"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true

for run in $(seq 1 "$RUNS"); do
  xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" >/dev/null 2>&1 || true
  sleep 3
  LOG="$TMP/run$run.log"
  : > "$LOG"

  T0=$(python3 -c 'import time;print(repr(time.time()))')
  ( xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" > "$LOG" 2>&1 ) &
  LAUNCHER=$!
  for _ in $(seq 1 100); do
    grep -q "S6_FIRST_RENDER" "$LOG" 2>/dev/null && break
    sleep 0.1
  done

  STAMP=$(grep -o "S6_FIRST_RENDER [0-9.]*" "$LOG" 2>/dev/null | head -1 | awk '{print $2}')
  if [ -n "$STAMP" ]; then
    python3 -c "print(f'RESULT cold_start_run${run}_ms={($STAMP-$T0)*1000:.0f}')"
  else
    echo "RESULT cold_start_run${run}_ms=NO_MARKER"
  fi

  sleep 4
  PID=$(pgrep -f "$APP_PATH/$EXE" 2>/dev/null | head -1)
  [ -z "$PID" ] && PID=$(pgrep -x "$EXE" 2>/dev/null | head -1)
  if [ -n "${PID:-}" ]; then
    RSS=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' ')
    [ -n "$RSS" ] && python3 -c "print(f'RESULT rss_run${run}_mb={$RSS/1024:.1f}')"
  else
    echo "RESULT rss_run${run}_mb=NO_PID"
  fi
  kill $LAUNCHER 2>/dev/null || true
done
