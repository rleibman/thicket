#!/usr/bin/env bash
# Builds the Scala half with sbt, hands the JAR to Gradle, installs and launches.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-$(cs java-home --jvm temurin:21)}"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"

"$root/modules/renderer-android/setup.sh" >/dev/null

echo "== sbt: building the Scala JARs =="
# Gradle needs the whole runtime classpath, not just the example's own classes, and sbt 2
# has no usable classpath export (it prints CAS placeholders). So package each module and
# collect the jars. scala3-library is left to Gradle's own dependency.
(cd "$root" && sbt --error \
  "signalsJVM/package" "rendererApiJVM/package" "coreJVM/package" \
  "rendererAndroid/package" "examplesSharedJVM/package" "todoAndroid/package" >/dev/null)

rm -f "$here"/app/libs/*.jar
for module in thicket-signals thicket-renderer-api thicket-core \
              thicket-renderer-android thicket-examples-shared todo-android; do
  # sbt 2 publishes artifacts as symlinks into a content-addressed store.
  # Exclude the `-tests.jar` the same directory also holds.
  src=$(readlink -f "$root"/target/out/jvm/scala-3.9.0/"$module"/"$module"_3-*[0-9T].jar)
  cp "$src" "$here/app/libs/$module.jar"
  printf "   %-34s %8d bytes\n" "$module.jar" "$(stat -c%s "$here/app/libs/$module.jar")"
done

echo "== gradle: assembling the APK =="
(cd "$here" && ./gradlew "${1:-assembleRelease}" --no-daemon -q)

apk=$(ls "$here"/app/build/outputs/apk/*/*.apk | head -1)
echo "== APK: $(stat -c%s "$apk") bytes =="

if adb get-state >/dev/null 2>&1; then
  adb install -r -g "$apk" >/dev/null
  adb shell am force-stop dev.thicket.todo
  adb logcat -c
  adb shell am start -W -n dev.thicket.todo/example.android.MainActivity \
    ${SELFTEST:+--ez selftest true} >/dev/null
  echo "== installed and launched =="
  if [ -n "${SELFTEST:-}" ]; then
    sleep 3
    adb logcat -d -s thicket 2>/dev/null | tr -d '\r' | sed -n 's/.*\[selftest\]/[selftest]/p'
  fi
else
  echo "(no device/emulator attached; skipping install)"
fi
