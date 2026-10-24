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
adb logcat -c
adb shell am start -W -n dev.thicket.gallery/example.gallery.android.GalleryActivity \
  ${SELFTEST:+--ez selftest true} >/dev/null
echo "== installed and launched =="
[ -n "${SELFTEST:-}" ] || exit 0

# Wait for the verdict rather than a fixed sleep: the checks are spread over main-loop turns.
for _ in $(seq 1 60); do
  if adb logcat -d -s thicket 2>/dev/null | grep -qE '\[gallery\] (ALL CHECKS PASSED|[0-9]+ CHECK)'; then break; fi
  sleep 1
done
log=$(adb logcat -d -s thicket 2>/dev/null | tr -d '\r' | sed -n 's/.*\[gallery\]/[gallery]/p')
echo "$log"
grep -q '\[gallery\] ALL CHECKS PASSED' <<<"$log"
