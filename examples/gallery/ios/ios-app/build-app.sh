#!/usr/bin/env bash
# Build the gallery's UIKit host, link it against the Apple shim and the Scala Native archive, and
# run the result on a simulator with the self-test on.
#
#   ./build-app.sh [device]        e.g. ./build-app.sh 'iPhone 17'
#
# Prerequisites, in order:
#   modules/renderer-apple/shim/build-shim.sh ios-sim
#   sbt galleryIos/nativeLink
#
# The steps shared by every example host are in examples/ios-host/run-ios-app.sh (#79).
# -lsqlite3: the gallery's SQLite is the system library, not the bundled amalgamation
# (build.sbt, appleSystemSqlite).
set -euo pipefail

DEVICE="${1:-iPhone 17}"
cd "$(dirname "$0")"

REPO=$(cd ../../../.. && pwd)
ARCHIVE="$REPO/target/out/native0.5/scala-3.9.0/gallery-ios/libgallery-ios.a"
SWIFT_LIB="$REPO/modules/renderer-apple/shim/build-ios/libthicketapple.a"

[ -f "$SWIFT_LIB" ] || { echo "Missing $SWIFT_LIB — run modules/renderer-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
[ -f "$ARCHIVE" ]   || { echo "Missing $ARCHIVE — run 'sbt galleryIos/nativeLink'" >&2; exit 1; }

APP_NAME=GalleryIos
BUNDLE_ID=dev.thicket.galleryios
SWIFT_INCLUDE="$REPO/modules/renderer-apple/shim/include"
EXTRA_LINK=-lsqlite3
# The same THICKET_GALLERY_SELFTEST the macOS example reads. Up to 6 min (#78, #114, #118): the gallery
# times 1 000 vs 2 000 children.
RUN_ENV=THICKET_GALLERY_SELFTEST=1
WAIT_SECONDS=360
SCREENSHOT=1
KEEP_UNSTRIPPED=1

# The Device section (#114): thicket-device-apple's Swift library; a fresh install, so the
# notifications alert is shown (uninstalling forgets the answer); the microphone granted with
# simctl privacy and its usage string, so asking answers Granted with no alert. Camera's usage
# string is left out on purpose: the self-test checks that asking without it throws, naming it.
DEVICE_LIB="$REPO/modules/device-apple/shim/build-ios/libthicketdevice.a"
[ -f "$DEVICE_LIB" ] || { echo "Missing $DEVICE_LIB — run modules/device-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
EXTRA_LIBS="$DEVICE_LIB"
FRESH_INSTALL=1
# Location (#118) granted too, with its usage string, without which iOS ignores the request.
PRIVACY_GRANT="microphone location"
USAGE_STRINGS="NSMicrophoneUsageDescription=The gallery checks that a granted permission answers Granted.
NSLocationWhenInUseUsageDescription=The gallery shows where the device is while it is in front."

# No simulated position before the launch, so the first one the self-test sets is a change it sees.
before_launch() { xcrun simctl location "$DEVICE" clear; }

# What the self-test asks the user to do: another app in front (Settings), the gallery launched
# again (the same process comes back), the notifications alert answered through XCUITest, and
# the device moved (`geo <lat>,<lon>`, #118).
drive() {
  case "$1" in
    home) xcrun simctl launch "$DEVICE" com.apple.Preferences ;;
    front) xcrun simctl launch "$DEVICE" "$BUNDLE_ID" ;;
    geo\ *) xcrun simctl location "$DEVICE" set "${1#geo }" ;;
    wait\ *) sleep "${1#wait }" ;;
    deny | allow) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" "$1" ;;
    *) echo "unknown DRIVE action: $1" ;;
  esac
}

# The gallery's images are repo-relative paths (`ImageSource.FromFile("docs/assets/...")`).
# An iOS app's working directory is `/`, so the shim looks a relative path up in the bundle;
# copy the assets in under the same relative path so it finds them there.
bundle_assets() {
  mkdir -p "$1/docs/assets"
  cp "$REPO/docs/assets/thicket-logo.png" "$1/docs/assets/"
}

source "$REPO/examples/ios-host/run-ios-app.sh"
