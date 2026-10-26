#!/usr/bin/env bash
# Build the todo app's UIKit host, link it against the Apple shim and the Scala Native archive,
# and run the result on a simulator with the self-test on.
#
#   ./build-app.sh [device]        e.g. ./build-app.sh 'iPhone 17'
#
# Prerequisites, in order:
#   modules/renderer-apple/shim/build-shim.sh ios-sim
#   sbt todoIos/nativeLink
#
# The steps shared by every example host are in examples/ios-host/run-ios-app.sh (#79).
set -euo pipefail

DEVICE="${1:-iPhone 17}"
cd "$(dirname "$0")"

REPO=$(cd ../../../.. && pwd)
ARCHIVE="$REPO/target/out/native0.5/scala-3.9.0/todo-ios/libtodo-ios.a"
SWIFT_LIB="$REPO/modules/renderer-apple/shim/build-ios/libthicketapple.a"

[ -f "$SWIFT_LIB" ] || { echo "Missing $SWIFT_LIB — run modules/renderer-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
[ -f "$ARCHIVE" ]   || { echo "Missing $ARCHIVE — run 'sbt todoIos/nativeLink'" >&2; exit 1; }

APP_NAME=TodoIos
BUNDLE_ID=dev.thicket.todoios
SWIFT_INCLUDE="$REPO/modules/renderer-apple/shim/include"
# The same THICKET_SELFTEST the macOS example reads. Up to 2 min (#78): the lazy probe
# (SIMCTL_CHILD_THICKET_LAZYTEST=1, set by the caller) scrolls all 10 000 rows.
RUN_ENV=THICKET_SELFTEST=1
WAIT_SECONDS=120
SCREENSHOT=1
KEEP_UNSTRIPPED=1

source "$REPO/examples/ios-host/run-ios-app.sh"
