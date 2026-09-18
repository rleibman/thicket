#!/bin/sh
# Links the Android SDK's android.jar where sbt's unmanaged `lib/` convention finds it.
set -e
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
mkdir -p "$(dirname "$0")/lib"
ln -sf "$ANDROID_HOME/platforms/android-36/android.jar" "$(dirname "$0")/lib/android.jar"
echo "linked $ANDROID_HOME/platforms/android-36/android.jar -> lib/android.jar"
