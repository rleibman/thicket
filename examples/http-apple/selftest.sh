#!/usr/bin/env bash
# The HTTP self-test (#90): URLSessionBackend and caliban-client against a local server.
#
#   examples/http-apple/selftest.sh macos            # after: sbt httpMacos/nativeLink
#   examples/http-apple/selftest.sh ios [device]     # after: sbt httpIos/nativeLink
#
# Starts server.py on 127.0.0.1 and a free port (the iOS simulator shares the Mac's loopback),
# runs the app with THICKET_HTTPTEST=<port>, and stops the server. Exit 0 only on
# "ALL CHECKS PASSED". Nothing leaves the machine.
set -euo pipefail
cd "$(dirname "$0")"
REPO=$(cd ../.. && pwd)

TARGET="${1:-macos}"
DEVICE="${2:-iPhone 17}"

mkdir -p build
PORT_FILE=build/port
rm -f "$PORT_FILE"
python3 server.py "$PORT_FILE" 2>build/server.log &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do [ -s "$PORT_FILE" ] && break; sleep 0.1; done
[ -s "$PORT_FILE" ] || { echo "the test server did not start; see build/server.log" >&2; exit 1; }
PORT=$(cat "$PORT_FILE")
echo "test server on 127.0.0.1:$PORT"

case "$TARGET" in
  macos)
    BIN="$REPO/target/out/native0.5/scala-3.9.0/http-macos/http-macos"
    [ -x "$BIN" ] || { echo "Missing $BIN — run 'sbt httpMacos/nativeLink'" >&2; exit 1; }
    LOG=build/macos.log
    THICKET_HTTPTEST="$PORT" "$BIN" 2>&1 | tee "$LOG"
    ;;
  ios)
    LOG=build/ios.log
    ios/ios-app/build-app.sh "$DEVICE" "$PORT" 2>&1 | tee "$LOG"
    ;;
  *)
    echo "unknown target: $TARGET (want macos | ios)" >&2
    exit 2
    ;;
esac

grep -q "ALL CHECKS PASSED" "$LOG"
