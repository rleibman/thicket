#!/bin/sh
# Links the Android SDK's android.jar where sbt's unmanaged `lib/` convention finds it.
# sbt 2 cannot put a plain File on a classpath (entries are HashedVirtualFileRef), so
# `unmanagedJars += Attributed.blank(file(...))` no longer type-checks — see S2.
set -e
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${ANDROID_PLATFORM:=android-36}"
dir=$(dirname "$0")
mkdir -p "$dir/lib"
ln -sf "$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar" "$dir/lib/android.jar"
echo "linked $ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar -> $dir/lib/android.jar"
