#!/usr/bin/env bash
# Does the Android getting-started actually work? The Android counterpart of
# bin/verify-getting-started.sh (docs/18).
#
# Copies templates/hello-thicket-android outside this repo, builds a debug APK with
# `sbt thicketApk` (sbt compiles, the template's own Gradle project packages), installs it on
# the attached emulator or device, and checks the app is up and working: its counter reads
# "Clicked 0 times", a tap on the button makes it "Clicked 1 times", and nothing crashed.
#
# Two modes, as in the others:
#   bin/verify-getting-started-android.sh                        publishes this checkout locally
#   THICKET_VERSION=0.3.0 bin/verify-getting-started-android.sh  a release, from Maven Central
#                                                                alone (empty caches; fails
#                                                                unless every thicket jar came
#                                                                from repo1.maven.org)
#
# Needs: the Android SDK (ANDROID_HOME, default ~/Android/Sdk) with platforms;android-36, a
# non-EA JDK 17+ for Gradle (JAVA_HOME, else coursier's temurin:21), and an emulator or device
# on adb. With none attached it fails, unless ALLOW_NO_DEVICE=1, which stops after the APK.
# VARIANT=release builds and checks the R8-shrunk APK (`sbt thicketApkRelease`) instead: the
# variant where Scala's run-time failures on Android live (docs/09 §9.3c).
set -euo pipefail

cd "$(dirname "$0")/.."
root=$(pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-$(cs java-home --jvm temurin:21)}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
app_id=dev.thicket.hello
variant="${VARIANT:-debug}"
case "$variant" in
  debug) task=thicketApk ;;
  release) task=thicketApkRelease ;;
  *) echo "VARIANT is debug or release, not $variant" >&2; exit 2 ;;
esac

if [ -n "${THICKET_VERSION:-}" ]; then
  ver="$THICKET_VERSION"
  echo "== a released version, $ver: resolving from Maven Central only =="
  export COURSIER_CACHE="$work/coursier"
  export SBT_OPTS="${SBT_OPTS:-} -Dsbt.ivy.home=$work/ivy -Dsbt.global.base=$work/sbt-global -Dsbt.boot.directory=$HOME/.sbt/boot"
else
  echo "== publishing the framework locally =="
  modules/renderer-android/setup.sh >/dev/null
  sbt --error \
    "signalsJVM/publishLocal; rendererApiJVM/publishLocal; coreJVM/publishLocal" \
    "rendererAndroid/publishLocal; sbtThicket/publishLocal"
  vers=$(sbt --error "print coreJVM/version; print sbtThicket/version" | tr -d '\r' |
    sed $'s/\x1b\\[[0-9;]*[A-Za-z]//g' | grep -E '^[0-9]' | tail -2 || true)
  ver=$(echo "$vers" | head -1)
  pluginver=$(echo "$vers" | tail -1)
  [ -n "$ver" ] || { echo "FAIL: could not read the version" >&2; exit 1; }
  # The plugin puts its own version on the thicket dependency it adds; one build makes both.
  [ "$ver" = "$pluginver" ] || { echo "FAIL: framework $ver but sbt-thicket $pluginver" >&2; exit 1; }
  echo "== version $ver =="
fi

cp -r templates/hello-thicket-android "$work/app"
cd "$work/app"
echo "== building a $variant APK outside the repo: sbt $task =="
THICKET_VERSION="$ver" sbt --error "$task"

apk=$(ls android/app/build/outputs/apk/"$variant"/*.apk 2>/dev/null | head -1)
[ -n "$apk" ] || { echo "FAIL: no APK" >&2; exit 1; }
echo "== APK: $(stat -c%s "$apk") bytes =="
echo "== libs: $(ls android/app/libs | tr '\n' ' ')=="

if [ -n "${THICKET_VERSION:-}" ]; then
  jars=$(find "$COURSIER_CACHE" -path '*/net/leibman/*' -name "*-$ver.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar')
  echo "$jars" | sed -E 's|.*/https/([^/]+)/.*/([^/]+)$|== resolved \2 from \1|'
  for m in sbt-thicket_sbt2_3 thicket-renderer-android_3 thicket-core_3 thicket-signals_3; do
    echo "$jars" | grep -q "/https/repo1.maven.org/maven2/net/leibman/$m/$ver/" ||
      { echo "FAIL: $m $ver was not downloaded from Maven Central" >&2; exit 1; }
  done
  if echo "$jars" | grep -v '/https/repo1.maven.org/' | grep -q .; then
    echo "FAIL: a thicket jar came from somewhere other than Maven Central" >&2; exit 1
  fi
fi

if ! adb get-state >/dev/null 2>&1; then
  if [ "${ALLOW_NO_DEVICE:-}" = 1 ]; then
    echo "PASS (build only): no emulator or device attached; the APK was not installed or run"
    exit 0
  fi
  echo "FAIL: no emulator or device on adb (ALLOW_NO_DEVICE=1 to stop after the build)" >&2; exit 1
fi

echo "== installing and launching on $(adb get-serialno) =="
adb install -r "$apk" >/dev/null
adb shell am force-stop "$app_id"
adb logcat -c
adb shell am start -W -n "$app_id/hello.MainActivity" >/dev/null

# The screen as uiautomator sees it: what a person would see, read from outside the app.
screen() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r'; }
# The centre of the node whose text is $1, from its bounds="[x1,y1][x2,y2]".
centre() {
  screen | grep -o "<node[^>]*text=\"$1\"[^>]*>" | grep -o 'bounds="[^"]*"' | head -1 |
    sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' |
    awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}
wait_for() {
  for _ in $(seq 1 20); do screen | grep -q "text=\"$1\"" && return 0; sleep 0.5; done
  echo "FAIL: \"$1\" never appeared on screen" >&2; return 1
}

wait_for "Clicked 0 times"
read -r x y <<<"$(centre "Click me")"
[ -n "${x:-}" ] || { echo "FAIL: no \"Click me\" button on screen" >&2; exit 1; }
adb shell input tap "$x" "$y"
wait_for "Clicked 1 times"

# Up, and still up: a crash after the first frame would show here.
sleep 5
pid=$(adb shell pidof "$app_id" | tr -d '\r' || true)
[ -n "$pid" ] || { echo "FAIL: the app is not running" >&2; adb logcat -d -s AndroidRuntime >&2; exit 1; }
if adb logcat -d -s AndroidRuntime | grep -q "FATAL EXCEPTION"; then
  echo "FAIL: a fatal exception was logged" >&2; adb logcat -d -s AndroidRuntime >&2; exit 1
fi
adb shell am force-stop "$app_id"

echo "PASS: a new Android app outside the repo builds ($variant), installs and runs against $ver${THICKET_VERSION:+ from Maven Central}: the counter read 0, a tap made it 1"
