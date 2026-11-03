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
# The same THICKET_GALLERY_SELFTEST the macOS example reads. Up to 8 min (#78, #114, #118, #125): the
# gallery times 1 000 vs 2 000 children, and the pickers' four taps take about 15 s each.
RUN_ENV=THICKET_GALLERY_SELFTEST=1
# 480 since #129: the camera gives its shutter 60 s to bring a photo back, and the simulator's never does.
# 600 with the pickers and the camera together (not measured yet; each alone fit in 480).
WAIT_SECONDS=600
SCREENSHOT=1
KEEP_UNSTRIPPED=1

# The Device section (#114): thicket-device-apple's Swift library; a fresh install, so the
# notifications alert is shown (uninstalling forgets the answer); the microphone granted with
# simctl privacy and its usage string, so asking answers Granted with no alert. (The camera's
# usage string was left out until #129; the macOS gallery still checks that it throws without it.)
DEVICE_LIB="$REPO/modules/device-apple/shim/build-ios/libthicketdevice.a"
[ -f "$DEVICE_LIB" ] || { echo "Missing $DEVICE_LIB — run modules/device-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
# The Motion section (#120): thicket-motion-ios's Swift library, over CMMotionManager.
MOTION_LIB="$REPO/modules/motion-ios/shim/build-ios/libthicketmotion.a"
[ -f "$MOTION_LIB" ] || { echo "Missing $MOTION_LIB — run modules/motion-ios/shim/build-shim.sh ios-sim" >&2; exit 1; }
# The Pickers section (#125): thicket-pickers-apple's Swift library.
PICKERS_LIB="$REPO/modules/pickers-apple/shim/build-ios/libthicketpickers.a"
[ -f "$PICKERS_LIB" ] || { echo "Missing $PICKERS_LIB — run modules/pickers-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
# The camera in the Pickers section (#129): thicket-camera-apple's Swift library, over
# UIImagePickerController, and the camera's usage string, without which iOS terminates the app
# when the camera opens (AppleCamera throws, naming it, instead). Granted below: the iOS 27
# simulator has a camera (a simulated grey frame), so the photo is taken through its screen.
CAMERA_LIB="$REPO/modules/camera-apple/shim/build-ios/libthicketcamera.a"
[ -f "$CAMERA_LIB" ] || { echo "Missing $CAMERA_LIB — run modules/camera-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
EXTRA_LIBS="$DEVICE_LIB $MOTION_LIB $PICKERS_LIB $CAMERA_LIB"
FRESH_INSTALL=1
# Location (#118) granted too, with its usage string, without which iOS ignores the request.
PRIVACY_GRANT="microphone location camera"
USAGE_STRINGS="NSMicrophoneUsageDescription=The gallery checks that a granted permission answers Granted.
NSLocationWhenInUseUsageDescription=The gallery shows where the device is while it is in front.
NSCameraUsageDescription=The gallery takes a photo with the camera screen."

# Files' "On My iPhone": the Files app's local storage, where the document picker opens the test
# document and saves the gallery's (#125).
on_my_iphone() {
  local group
  group=$(xcrun simctl get_app_container "$DEVICE" com.apple.DocumentsApp groups |
    sed -n 's/^group\.com\.apple\.FileProvider\.LocalStorage[[:space:]]*//p')
  [ -n "$group" ] || { echo "no Files local storage on $DEVICE" >&2; return 1; }
  echo "$group/File Provider Storage"
}

# No simulated position before the launch, so the first one the self-test sets is a change it sees.
# What the pickers pick (#125): the gallery's logo in the photo library, newest, so first in the
# picker; a text file in "On My iPhone"; and no saved file left from an earlier run.
before_launch() {
  xcrun simctl location "$DEVICE" clear
  xcrun simctl addmedia "$DEVICE" "$REPO/docs/assets/thicket-logo.png"
  local files
  files=$(on_my_iphone)
  mkdir -p "$files"
  printf 'a test document\n' >"$files/thicket-test.txt"
  rm -f "$files/thicket-gallery.txt"
}

# The save's check, from outside the app: the file in "On My iPhone" holds the gallery's text.
check_saved() {
  local f
  f="$(on_my_iphone)/thicket-gallery.txt"
  for _ in $(seq 1 10); do [ -f "$f" ] && break; sleep 1; done
  [ "$(cat "$f" 2>/dev/null)" = "Saved from the Thicket gallery" ] || {
    echo "the saved file is not in On My iPhone with the gallery's text: $(ls -la "$f" 2>&1)"
    return 1
  }
  echo "-- saved: $f ($(stat -f%z "$f") bytes), the gallery's text"
}

# The Share section (#134): "Copy" tapped in the gallery's share sheet through XCUITest, then the
# simulator's pasteboard read from outside the app, which must hold the text the gallery shared.
share_copy() {
  "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" "$BUNDLE_ID|Copy" || return 1
  local got=""
  for _ in 1 2 3 4 5; do
    got=$(xcrun simctl pbpaste "$DEVICE" 2>/dev/null || true)
    [ "$got" = "$1" ] && break
    sleep 1
  done
  echo "-- after \"Copy\" in the share sheet, simctl pbpaste reads \"$got\"; the gallery shared \"$1\""
  [ "$got" = "$1" ]
}

# What the self-test asks the user to do: another app in front (Settings), the gallery launched
# again (the same process comes back), the notifications alert answered through XCUITest, the
# device moved (`geo <lat>,<lon>`, #118), the pickers answered through XCUITest (#125), the camera
# screen's buttons through XCUITest (#129), and
# "Copy" in the share sheet (`share-copy <text>`, #134).
drive() {
  case "$1" in
    home) xcrun simctl launch "$DEVICE" com.apple.Preferences ;;
    front) xcrun simctl launch "$DEVICE" "$BUNDLE_ID" ;;
    geo\ *) xcrun simctl location "$DEVICE" set "${1#geo }" ;;
    wait\ *) sleep "${1#wait }" ;;
    deny | allow) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" "$1" ;;
    pick-photo) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" photo "$BUNDLE_ID" ;;
    cancel-photo) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" cancel "$BUNDLE_ID" ;;
    pick-document) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" document "$BUNDLE_ID" thicket-test ;;
    save-document) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" export "$BUNDLE_ID" && check_saved ;;
    # The camera screen (#129): the shutter, then "Use Photo" if a review screen comes up (the
    # simulator's shutter takes nothing, so none does); or its X.
    photo) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" "app $BUNDLE_ID Take Picture|?Use Photo" ;;
    cancel) "$REPO/examples/ios-host/tapper/tap.sh" "$DEVICE" "app $BUNDLE_ID Dismiss" ;;
    share-copy\ *) share_copy "${1#share-copy }" ;;
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
