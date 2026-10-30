#!/usr/bin/env bash
# What every example's iOS host build has in common (#79): link the host's Swift against a
# Swift static library and the app's Scala Native archive, bundle it with an Info.plist made
# from Info.plist.in, install it on a simulator, run it with one environment variable set, and
# wait for its verdict line.
#
# Sourced by each example's ios-app/build-app.sh, from its own ios-app directory, after it has
# set the variables below. Sources/ (Host.swift and the bridging header) stays per app: the
# Scala entry symbol is per app, and HostEntrySpec checks each host against its app's Scala.
# The template (templates/hello-thicket-ios) keeps a complete build-app.sh of its own: it is
# copied out of this repository, where this file is not.
#
#   APP_NAME   executable and bundle name, e.g. GalleryIos
#   BUNDLE_ID  e.g. dev.thicket.galleryios
#   ARCHIVE    the app's Scala Native static archive (sbt <app>Ios/nativeLink)
#   SWIFT_LIB  the Swift static library the archive calls
#   RUN_ENV    KEY=VALUE, passed to the app through SIMCTL_CHILD_
#   DEVICE     simulator name
# Optional: SWIFT_INCLUDE (a -I for the bridging header), EXTRA_LINK (e.g. -lsqlite3),
# WAIT_SECONDS (default 120), SCREENSHOT=1, KEEP_UNSTRIPPED=1, and a function
# `bundle_assets <app dir>` defined before sourcing.
# For the device checks (#114): EXTRA_LIBS (more Swift static libraries, e.g.
# libthicketdevice.a), USAGE_STRINGS (lines of "Key=text" added to the Info.plist, e.g.
# NSMicrophoneUsageDescription), FRESH_INSTALL=1 (uninstall first, which also forgets the
# notifications answer), PRIVACY_GRANT (simctl privacy services granted before the launch), and a
# function `drive <action>`, called once for each `[gallery] DRIVE <action>` line the app prints.
set -euo pipefail

: "${APP_NAME:?}" "${BUNDLE_ID:?}" "${ARCHIVE:?}" "${SWIFT_LIB:?}" "${RUN_ENV:?}" "${DEVICE:?}"
HOST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
APP="build/$APP_NAME.app"
rm -rf build && mkdir -p "$APP"

# One swiftc invocation compiles the host and resolves both directions: the library's
# @_cdecl functions satisfy the archive's undefined symbols, and the archive's @exported
# symbols satisfy the host's calls.
#
# clang warns "using sysroot for 'macOS 27.0' but targeting arm64-apple-ios17.0-simulator"
# here. It comes from swiftc's ClangImporter, which does not inherit -sdk for the -Xcc
# flags; passing -Xcc -isysroot explicitly does not silence it. Cosmetic: the bridging
# header is types-only, nothing platform-dependent is imported through it, and the link
# itself uses the simulator SDK.
echo "=== linking host + $(basename "$SWIFT_LIB") + $(basename "$ARCHIVE") ==="
flags=()
[ -n "${SWIFT_INCLUDE:-}" ] && flags+=(-Xcc "-I$SWIFT_INCLUDE")
# The simulator reads an app's entitlements from a __TEXT,__entitlements section, as Xcode's
# "Sign to Run Locally" writes it; entitlements in an ad-hoc code signature get the app killed at
# launch instead. The Keychain needs them: without, every call is errSecMissingEntitlement and
# thicket-secrets-apple's open returns Left (#108, both measured on the iOS 27 simulator). The
# app's own keychain group, named by its bundle id.
cat >build/entitlements.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>application-identifier</key>
  <string>$BUNDLE_ID</string>
  <key>keychain-access-groups</key>
  <array><string>$BUNDLE_ID</string></array>
</dict>
</plist>
EOF
flags+=(-Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker build/entitlements.plist)
swiftc \
  -target arm64-apple-ios17.0-simulator \
  -sdk "$SDK" \
  -parse-as-library \
  -import-objc-header Sources/Thicket-Bridging-Header.h \
  ${flags[@]+"${flags[@]}"} \
  Sources/Host.swift \
  "$SWIFT_LIB" ${EXTRA_LIBS:-} "$ARCHIVE" \
  ${EXTRA_LINK:-} \
  -o "$APP/$APP_NAME"

# One Info.plist for every example — a UIScene manifest, without which iOS 27 will not launch
# the app (S3) — with only the names filled in.
sed -e "s/@APP_NAME@/$APP_NAME/g" -e "s/@BUNDLE_ID@/$BUNDLE_ID/g" "$HOST_DIR/Info.plist.in" >"$APP/Info.plist"
# A usage string the app asks for: without it iOS terminates an app that asks (thicket-device-apple
# throws first, naming the key).
while IFS= read -r usage; do
  [ -n "$usage" ] || continue
  /usr/libexec/PlistBuddy -c "Add :${usage%%=*} string ${usage#*=}" "$APP/Info.plist"
done <<<"${USAGE_STRINGS:-}"
if declare -F bundle_assets >/dev/null; then bundle_assets "$APP"; fi

echo "RESULT app_unstripped_bytes=$(stat -f%z "$APP/$APP_NAME")"
if [ "${KEEP_UNSTRIPPED:-0}" = 1 ]; then cp "$APP/$APP_NAME" "build/$APP_NAME.unstripped"; fi
strip -x "$APP/$APP_NAME" 2>/dev/null || true
echo "RESULT app_stripped_bytes=$(stat -f%z "$APP/$APP_NAME")"

# Signed ad hoc, after stripping (which would break the signature): an unsigned app on the
# simulator gets errSecMissingEntitlement from every Keychain call, so thicket-secrets-apple's
# open returns Left (#108, measured on the iOS 27 simulator).
codesign --force --sign - "$APP"

xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b >/dev/null 2>&1 || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
if [ "${FRESH_INSTALL:-0}" = 1 ]; then xcrun simctl uninstall "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true; fi
xcrun simctl install "$DEVICE" "$APP"
for service in ${PRIVACY_GRANT:-}; do xcrun simctl privacy "$DEVICE" grant "$service" "$BUNDLE_ID"; done

# SIMCTL_CHILD_ passes an environment variable through to the app, which is how a self-test
# is switched on.
#
# --console-pty, backgrounded: a simulator app's stdout does not reach os_log, so
# `log show` cannot see println output — only a pty can. It never returns on its own, so
# it is run detached, given time to finish, and then stopped.
LOG=build/run.log
env "SIMCTL_CHILD_$RUN_ENV" xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" >"$LOG" 2>&1 &
LAUNCHER=$!
# The verdict line ends the wait, so a passing run is no slower for a long limit.
# Each DRIVE line is acted on once, in order, by the app's own `drive`.
driven=0
for _ in $(seq 1 "${WAIT_SECONDS:-120}"); do
  grep -q "ALL CHECKS PASSED\|CHECK(S) FAILED" "$LOG" 2>/dev/null && break
  if declare -F drive >/dev/null; then
    lines=$(grep -a "\[gallery\] DRIVE " "$LOG" 2>/dev/null | tr -d "\r" | sed "s/.*DRIVE //" || true)
    total=$(printf "%s" "$lines" | grep -c . || true)
    while [ "$driven" -lt "$total" ]; do
      driven=$((driven + 1))
      action=$(printf "%s\n" "$lines" | sed -n "${driven}p")
      echo "=== DRIVE $action ==="
      drive "$action" || echo "=== DRIVE $action failed ==="
    done
  fi
  sleep 1
done

if [ "${SCREENSHOT:-0}" = 1 ]; then
  xcrun simctl io "$DEVICE" screenshot "build/$APP_NAME.png" >/dev/null 2>&1 || true
fi
kill "$LAUNCHER" 2>/dev/null || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true

echo "=== app output ==="
cat "$LOG"
echo "=== end ==="
grep -q "ALL CHECKS PASSED" "$LOG"
