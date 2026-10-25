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
# The same THICKET_GALLERY_SELFTEST the macOS example reads. Up to 2 min (#78): the gallery
# times 1 000 vs 2 000 children.
RUN_ENV=THICKET_GALLERY_SELFTEST=1
WAIT_SECONDS=120
SCREENSHOT=1
KEEP_UNSTRIPPED=1

# The gallery's images are repo-relative paths (`ImageSource.FromFile("docs/assets/...")`).
# An iOS app's working directory is `/`, so the shim looks a relative path up in the bundle;
# copy the assets in under the same relative path so it finds them there.
bundle_assets() {
  mkdir -p "$1/docs/assets"
  cp "$REPO/docs/assets/thicket-logo.png" "$1/docs/assets/"
}

source "$REPO/examples/ios-host/run-ios-app.sh"
