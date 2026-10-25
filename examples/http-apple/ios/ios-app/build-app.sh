#!/usr/bin/env bash
# Link the HTTP self-test's iOS host against thicket-http-apple's Swift library and the Scala
# Native archive, and run it on a simulator against the server on <port>.
#
#   ./build-app.sh <device> <port>        normally run by examples/http-apple/selftest.sh ios
#
# Prerequisites:
#   modules/http-apple/shim/build-shim.sh ios-sim   (sbt does this when it packages httpApple)
#   sbt httpIos/nativeLink
#
# Note what is not linked: modules/renderer-apple's libthicketapple.a. HTTP needs no renderer.
# The steps shared by every example host are in examples/ios-host/run-ios-app.sh (#79).
set -euo pipefail

DEVICE="${1:-iPhone 17}"
PORT="${2:?usage: build-app.sh <device> <port>}"
cd "$(dirname "$0")"

REPO=$(cd ../../../.. && pwd)
ARCHIVE="$REPO/target/out/native0.5/scala-3.9.0/http-ios/libhttp-ios.a"
SWIFT_LIB="$REPO/modules/http-apple/shim/build-ios/libthickethttp.a"

[ -f "$SWIFT_LIB" ] || { echo "Missing $SWIFT_LIB — run modules/http-apple/shim/build-shim.sh ios-sim" >&2; exit 1; }
[ -f "$ARCHIVE" ]   || { echo "Missing $ARCHIVE — run 'sbt httpIos/nativeLink'" >&2; exit 1; }

APP_NAME=HttpIos
BUNDLE_ID=dev.thicket.httpios
# The port of the server selftest.sh started, passed through to the app's environment.
RUN_ENV="THICKET_HTTPTEST=$PORT"
WAIT_SECONDS=60

source "$REPO/examples/ios-host/run-ios-app.sh"
