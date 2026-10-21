#!/bin/sh
# Links the Android SDK's android.jar where sbt's unmanaged `lib/` convention finds it.
# sbt 2 cannot put a plain File on a classpath (entries are HashedVirtualFileRef), so
# `unmanagedJars += Attributed.blank(file(...))` no longer type-checks — see S2.
set -e
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${ANDROID_PLATFORM:=android-36}"
dir=$(dirname "$0")
jar="$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar"
# Checked first: `ln -sf` to a missing file succeeds and leaves a dangling link, and the
# failure then surfaces later as a wall of "not found: android" compile errors.
if [ ! -f "$jar" ]; then
  echo "no $jar — install it with: sdkmanager \"platforms;$ANDROID_PLATFORM\"" >&2
  exit 1
fi
mkdir -p "$dir/lib"
ln -sf "$jar" "$dir/lib/android.jar"
echo "linked $ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar -> $dir/lib/android.jar"
