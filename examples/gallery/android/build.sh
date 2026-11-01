#!/usr/bin/env bash
# Builds the gallery's Scala half with sbt, hands the JARs to Gradle, installs and launches.
#   ./build.sh                    assemble the release APK, install and launch if a device is attached
#   SELFTEST=1 ./build.sh         ...and run the gallery self-test, printing its checks
# The self-test's verdict is the exit status: 0 only on "ALL CHECKS PASSED".
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-$(cs java-home --jvm temurin:21)}"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"

"$root/modules/renderer-android/setup.sh" >/dev/null

echo "== sbt: building the Scala JARs =="
(cd "$root" && sbt --error \
  "signalsJVM/package" "rendererApiJVM/package" "coreJVM/package" "effectZioJVM/package" \
  "storageJVM/package" "sqliteJVM/package" "storageAndroid/package" "sqliteAndroid/package" \
  "secretsJVM/package" "secretsAndroid/package" "deviceJVM/package" "deviceAndroid/package" "locationJVM/package" "locationAndroid/package" \
  "motionJVM/package" "motionAndroid/package" \
  "clipboardJVM/package" "clipboardAndroid/package" "hapticsJVM/package" "hapticsAndroid/package" \
  "httpChecksJVM/package" "httpAndroid/package" \
  "rendererAndroid/package" "gallerySharedJVM/package" "galleryAndroid/package" >/dev/null)

# The version and the Scala version, asked rather than assumed: both are in the jar paths.
vers=$(cd "$root" && sbt --error "print signalsJVM/version; print signalsJVM/scalaVersion" 2>/dev/null | tr -d '\r' | sed $'s/\x1b\\[[0-9;]*[A-Za-z]//g' | grep -E '^[0-9]' | tail -2)
ver=$(sed -n 1p <<<"$vers")
sv=$(sed -n 2p <<<"$vers")
[ -n "$ver" ] && [ -n "$sv" ] || { echo "could not read the versions from sbt" >&2; exit 1; }
echo "   version $ver, Scala $sv"

mkdir -p "$here/app/libs"
rm -f "$here"/app/libs/*.jar
for module in thicket-signals thicket-renderer-api thicket-core thicket-effect-zio \
              thicket-storage thicket-sqlite thicket-storage-android thicket-sqlite-android \
              thicket-secrets thicket-secrets-android thicket-device thicket-device-android \
              thicket-location thicket-location-android thicket-motion thicket-motion-android \
              thicket-clipboard thicket-clipboard-android thicket-haptics thicket-haptics-android \
              http-checks thicket-http-android \
              thicket-renderer-android thicket-gallery-shared gallery-android; do
  # sbt 2 publishes artifacts as symlinks into a content-addressed store, hence readlink.
  src=$(readlink -f "$root/target/out/jvm/scala-$sv/$module/${module}_3-$ver.jar")
  cp "$src" "$here/app/libs/$module.jar"
  printf "   %-34s %8d bytes\n" "$module.jar" "$(stat -c%s "$here/app/libs/$module.jar")"
done

# The gallery names its image by the path every host uses; on Android that path is resolved
# in the APK's assets.
mkdir -p "$here/app/src/main/assets/docs/assets"
cp "$root/docs/assets/thicket-logo.png" "$here/app/src/main/assets/docs/assets/"

echo "== gradle: assembling the APK =="
(cd "$here" && ./gradlew "${1:-assembleRelease}" --no-daemon -q)

apk=$(ls "$here"/app/build/outputs/apk/*/*.apk | head -1)
echo "== APK: $(stat -c%s "$apk") bytes =="

if ! adb get-state >/dev/null 2>&1; then
  echo "(no device/emulator attached; skipping install)"
  exit 0
fi
adb install -r -g "$apk" >/dev/null
adb shell am force-stop dev.thicket.gallery
pkg=dev.thicket.gallery
activity=$pkg/example.gallery.android.GalleryActivity
if [ -n "${SELFTEST:-}" ]; then
  # `install -g` granted every permission; the self-test asks for this one through the system's dialog, so it starts
  # revoked and with no "don't ask again" left from an earlier run.
  adb shell pm revoke $pkg android.permission.POST_NOTIFICATIONS
  adb shell pm clear-permission-flags $pkg android.permission.POST_NOTIFICATIONS user-set user-fixed
fi
adb logcat -c
# The device's clock now: the vibrator's history outlives a run, so only taps recorded after this count.
started_at=$(adb shell "date '+%m-%d %H:%M:%S'" | tr -d '\r')
# The self-test's HTTP checks talk to examples/http/server.py on this machine's loopback, which `adb reverse` makes
# the emulator's 127.0.0.1 too. Nothing leaves the machine.
httparg=()
if [ -n "${SELFTEST:-}" ]; then
  portfile=$(mktemp)
  python3 "$root/examples/http/server.py" "$portfile" 2>"$here/app/build/http-server.log" &
  server=$!
  trap 'kill $server 2>/dev/null || true; adb reverse --remove-all >/dev/null 2>&1 || true; rm -f "$portfile"' EXIT
  for _ in $(seq 1 50); do [ -s "$portfile" ] && break; sleep 0.1; done
  [ -s "$portfile" ] || { echo "the HTTP test server did not start" >&2; exit 1; }
  port=$(cat "$portfile")
  adb reverse "tcp:$port" "tcp:$port" >/dev/null
  httparg=(--ei httptest "$port")
fi
# A fresh task: a permission dialog left in the old one by an aborted run would otherwise come back to the front and
# swallow this launch, so the self-test never starts.
adb shell am start -W --activity-clear-task -n $activity ${SELFTEST:+--ez selftest true} "${httparg[@]}" >/dev/null
echo "== installed and launched =="
[ -n "${SELFTEST:-}" ] || exit 0

# Taps a button of the system's permission dialog, found by its id in a uiautomator dump, as a user would. The dialog
# may not be up yet at the first dump, so finding nothing is not an error here (set -e would make it one).
tap_dialog() { # resource id
  local id=$1 b
  for _ in $(seq 1 30); do
    adb shell rm -f /sdcard/thicket-ui.xml
    adb shell uiautomator dump /sdcard/thicket-ui.xml >/dev/null 2>&1 || true
    b=$(adb shell cat /sdcard/thicket-ui.xml 2>/dev/null | tr '>' '\n' \
      | grep "resource-id=\"com.android.permissioncontroller:id/$id\"" \
      | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | head -1 || true)
    if [ -n "$b" ]; then
      read -r x1 y1 x2 y2 <<<"$b"
      adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
      echo "   (build.sh) tapped $id"
      return 0
    fi
    sleep 0.5
  done
  echo "   (build.sh) no $id on screen"
}

# What the self-test asks for with "[gallery] DRIVE <action>": what only the system or the user can do.
drive() {
  case $1 in
    home) adb shell input keyevent KEYCODE_HOME ;;
    front) adb shell am start -n $activity --activity-reorder-to-front >/dev/null ;;
    deny) tap_dialog permission_deny_button ;;
    allow) tap_dialog permission_allow_button ;;
    # "geo <lon> <lat>": the emulator's GPS reports there. The two numbers are split into two arguments on purpose.
    geo\ *) adb emu geo fix ${1#geo } >/dev/null ;;
    # "accel x:y:z" and "gyro x:y:z": the emulator's accelerometer and gyroscope report these from now on.
    accel\ *) adb emu sensor set acceleration "${1#accel }" >/dev/null ;;
    gyro\ *) adb emu sensor set gyroscope "${1#gyro }" >/dev/null ;;
    *) echo "   (build.sh) unknown action $1" ;;
  esac
}

# Wait for the verdict rather than a fixed sleep: the checks are spread over main-loop turns. Meanwhile, act on each
# DRIVE line once, in order.
done_drives=0
for _ in $(seq 1 180); do
  current=$(adb logcat -d -s thicket 2>/dev/null | tr -d '\r')
  mapfile -t drives < <(sed -n 's/.*\[gallery\] DRIVE //p' <<<"$current")
  while [ "$done_drives" -lt "${#drives[@]}" ]; do
    echo "   (build.sh) ${drives[$done_drives]}"
    drive "${drives[$done_drives]}"
    done_drives=$((done_drives + 1))
  done
  if grep -qE '\[gallery\] (ALL CHECKS PASSED|[0-9]+ CHECK)' <<<"$current"; then break; fi
  sleep 1
done

# Back at the root finishes the Activity: the app's terminate hook runs, and says so.
adb shell input keyevent KEYCODE_BACK
for _ in $(seq 1 10); do
  adb logcat -d -s thicket 2>/dev/null | grep -q '\[gallery\] TERMINATED' && break
  sleep 0.5
done
log=$(adb logcat -d -s thicket 2>/dev/null | tr -d '\r' | sed -n 's/.*\[gallery\]/[gallery]/p')
echo "$log"
verdict=0
grep -q '\[gallery\] ALL CHECKS PASSED' <<<"$log" || verdict=1
grep -q '\[gallery\] TERMINATED' <<<"$log" || { echo "FAIL: Back did not run the terminate hook"; verdict=1; }
# The Clipboard and haptics section's tap, as the system's vibrator recorded it: evidence from outside the app.
if [ -n "${SELFTEST:-}" ]; then
  taps=$(adb shell dumpsys vibrator_manager | tr -d '\r' |
    grep 'dev.thicket.gallery' | grep 'performHapticFeedback' |
    awk -v since="$started_at" '{ if (($1 " " $2) >= since) n++ } END { print n + 0 }')
  if [ "$taps" -gt 0 ]; then echo "ok: the vibrator played $taps of the gallery's haptic taps"
  else echo "FAIL: the vibrator recorded none of the gallery's haptic taps since $started_at"; verdict=1; fi
fi
exit $verdict
