#!/usr/bin/env bash
# S2 measurements: installs both APKs, records cold-start times and runtime errors.
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cd "$(dirname "$0")/android"

SCALA_APK=app-scala/build/outputs/apk/release/app-scala-release.apk
KOTLIN_APK=app-kotlin/build/outputs/apk/release/app-kotlin-release.apk

echo "=== APK sizes ==="
for a in "$SCALA_APK" "$KOTLIN_APK"; do printf "%9d  %s\n" "$(stat -c%s "$a")" "$a"; done

adb install -r -g "$SCALA_APK"  > /dev/null
adb install -r -g "$KOTLIN_APK" > /dev/null
echo "installed"

cold_start() { # $1=package/activity  $2=label
  local total=() t
  for i in $(seq 1 10); do
    adb shell am force-stop "${1%%/*}" > /dev/null
    sleep 1
    t=$(adb shell am start -W -S "$1" 2>/dev/null | tr -d '\r' | awk -F: '/^TotalTime/{print $2}')
    total+=("$t")
  done
  printf "%s cold starts (ms): %s\n" "$2" "${total[*]}"
  printf "%s median: %s ms\n" "$2" \
    "$(printf '%s\n' "${total[@]}" | sort -n | awk '{a[NR]=$1} END{print (NR%2)?a[(NR+1)/2]:int((a[NR/2]+a[NR/2+1])/2)}')"
}

adb logcat -c
cold_start "scalaui.s2/scalaui.s2.MainActivity"   "SCALA "
cold_start "scalaui.s2k/scalaui.s2k.MainActivity" "KOTLIN"

echo "=== runtime check: does the Scala activity actually work? ==="
adb shell am force-stop scalaui.s2 > /dev/null
adb logcat -c
adb shell am start -W scalaui.s2/scalaui.s2.MainActivity > /dev/null
sleep 3
echo "--- our log lines ---"
adb logcat -d -s S2:* 2>/dev/null | tr -d '\r' | tail -5
echo "--- any crash / VerifyError / NoClassDefFound ---"
adb logcat -d 2>/dev/null | tr -d '\r' \
  | grep -iE "scalaui|VerifyError|NoClassDefFound|NoSuchMethod|AndroidRuntime" \
  | grep -viE "^$" | tail -20 || echo "(none)"
