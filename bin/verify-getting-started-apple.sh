#!/usr/bin/env bash
# Does the Apple getting-started actually work? The macOS and iOS counterpart of
# bin/verify-getting-started.sh, and it needs a Mac with Xcode.
#
# Publishes the framework locally — including thicket-renderer-apple, whose jar now carries the
# Swift shim (#41) — copies templates/hello-thicket-macos and templates/hello-thicket-ios
# outside this repo, and builds and runs each against the published artefacts. Building inside
# the repo would resolve the modules as project dependencies and prove nothing about what a
# stranger gets.
#
# "It came up" is measured, not assumed:
#   macOS  the app is still running after 10s, it owns a window, and no crash report appeared;
#   iOS    the app is still running in the simulator after 10s (ios-app/build-app.sh checks).
set -euo pipefail

cd "$(dirname "$0")/.."
root=$(pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
device="${1:-iPhone 17}"

echo "== publishing the framework locally (needs Xcode: the renderer's jar carries the Swift shim) =="
for t in signalsNative rendererApiNative coreNative rendererApple sbtThicket; do
  sbt --error "$t/publishLocal"
done

# The version, wherever it is on the line. sbt's thin client surrounds it with "[success]"
# lines and terminal escape sequences, and under the CI runner an escape *precedes* the
# version on its own line — so escapes are stripped and the version matched anywhere, not
# at the start. `|| true`: under `pipefail` a grep that finds nothing would otherwise end the
# script silently, before the message below could say why.
version_of() {
  sbt --error "print $1/version" | tr -d '\r' | sed $'s/\x1b\\[[0-9;]*[A-Za-z]//g' |
    grep -oE '[0-9]+\.[0-9]+\.[0-9]+[^[:space:]]*' | tail -1 || true
}
ver=$(version_of coreNative)
pluginver=$(version_of sbtThicket)
[ -n "$ver" ] || { echo "FAIL: could not read the version from sbt" >&2; exit 1; }
# The plugin puts its own version on the dependencies it adds; one build makes both, so they
# agree — checked, because the failure would be a resolution error in someone else's project.
[ "$ver" = "$pluginver" ] || { echo "FAIL: framework $ver but sbt-thicket $pluginver" >&2; exit 1; }
echo "== version $ver =="

# --- macOS -------------------------------------------------------------------------------
cp -r templates/hello-thicket-macos "$work/mac"
cd "$work/mac"
echo "== macOS: building the template outside the repo, against the published jars =="
THICKET_VERSION="$ver" sbt --error nativeLink
bin=$(find . -type f -name hello-thicket -perm -u+x | head -1)
[ -n "$bin" ] || { echo "no binary produced" >&2; exit 1; }
echo "== binary: $(stat -f%z "$bin") bytes =="

crashes_before=$(ls ~/Library/Logs/DiagnosticReports 2>/dev/null | grep -c '^hello-thicket' || true)
"$bin" >"$work/mac-run.log" 2>&1 &
pid=$!
sleep 10
if ! kill -0 "$pid" 2>/dev/null; then
  echo "FAIL: the macOS app exited within 10s" >&2; cat "$work/mac-run.log" >&2; exit 1
fi

# A window owned by the process. Owner PIDs are visible to anyone; titles only with
# screen-recording permission, so they are reported when available rather than required.
cat >"$work/windows.swift" <<'SWIFT'
import CoreGraphics
let pid = Int(CommandLine.arguments[1])!
let all = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] ?? []
let mine = all.filter { ($0[kCGWindowOwnerPID as String] as? Int) == pid }
print("windows=\(mine.count) titles=\(mine.compactMap { $0[kCGWindowName as String] as? String })")
SWIFT
windows=$(swift "$work/windows.swift" "$pid" 2>/dev/null || echo "windows=?")
kill "$pid" 2>/dev/null || true
echo "== macOS: $windows =="
case "$windows" in
  windows=0*|windows=\?*) echo "FAIL: the macOS app has no window" >&2; exit 1 ;;
esac
crashes_after=$(ls ~/Library/Logs/DiagnosticReports 2>/dev/null | grep -c '^hello-thicket' || true)
[ "$crashes_after" = "$crashes_before" ] || { echo "FAIL: a crash report appeared" >&2; exit 1; }
if grep -qiE "Unhandled signal|uncaught|Fatal error" "$work/mac-run.log"; then
  echo "FAIL: the macOS app reported a problem:" >&2; cat "$work/mac-run.log" >&2; exit 1
fi
echo "PASS: macOS — outside the repo, it resolves, links and runs against $ver"

# --- iOS simulator -----------------------------------------------------------------------
cd "$root"
cp -r templates/hello-thicket-ios "$work/ios"
echo "== iOS: building the template outside the repo, and running it on '$device' =="
THICKET_VERSION="$ver" "$work/ios/ios-app/build-app.sh" "$device"
echo "PASS: iOS simulator — outside the repo, it resolves, links and runs against $ver"
