#!/usr/bin/env bash
# Stage a Thicket release on Maven Central: the Mac builds, this machine signs and uploads.
#
#   THICKET_MAC_HOST=<mac> THICKET_GPG_KEY=<fingerprint> bin/release.sh 0.3.0
#
# The route the first release took (0.2.0, 2026-11-09; docs/14 §14.5):
#
#   1. The Mac clones the tag `v<version>` and runs `publish; platforms/publish` unsigned into
#      `target/sona-staging`. Only a Mac can build every artefact: seven Apple modules carry
#      Swift libraries that need Xcode, and off a Mac their `publish` is skipped. The Mac needs
#      no gpg and no Central token.
#   2. The staging directory comes back here, without macOS's AppleDouble `._*` files.
#   3. Every artefact is checked (version, the four files per module, checksums, the POM
#      fields Central requires, no SNAPSHOT dependency), then signed with your key here, and
#      every signature verified.
#   4. The bundle is uploaded to the Central Portal as USER_MANAGED and the script waits for
#      its validation. **It does not publish.** Pressing Publish in the Portal is yours, and
#      it is the only permanent step: a Central release can never be withdrawn or replaced.
#
# Needs, here: ssh to the Mac without a password, gpg with the signing key, the Android SDK's
# android.jar (the Mac has no Android SDK; it is copied over), and SONATYPE_USERNAME /
# SONATYPE_PASSWORD (a Central Portal user token) in the environment.
# Needs, on the Mac: git, sbt, Xcode.
#
# RELEASE_UPLOAD=0 stops after signing, leaving the bundle in the work directory: a rehearsal
# of everything but the upload.
set -euo pipefail

ver="${1:?usage: bin/release.sh <version>, e.g. 0.3.0 — the tag v<version> must be on origin}"
mac="${THICKET_MAC_HOST:?set THICKET_MAC_HOST to the Mac that builds the jars (ssh host)}"
key="${THICKET_GPG_KEY:?set THICKET_GPG_KEY to the fingerprint of the signing key}"
upload="${RELEASE_UPLOAD:-1}"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${ANDROID_PLATFORM:=android-36}"
android_jar="$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar"
repo_url="https://github.com/rleibman/thicket.git"
portal="https://central.sonatype.com/api/v1/publisher"

cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
ssh_mac() { ssh -o BatchMode=yes "$mac" "$@"; }

# --- preconditions ---------------------------------------------------------------------------
# A release version only: anything else would be refused by build.sbt's publishTo anyway, but
# failing here says why before the Mac spends a build on it.
[[ "$ver" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-(M|RC)[0-9]+)?$ ]] || fail "$ver is not a release version (X.Y.Z, -M<n>, -RC<n>)"
git fetch -q origin main "refs/tags/v$ver:refs/tags/v$ver" 2>/dev/null || fail "no tag v$ver on origin"
commit=$(git rev-parse "v$ver^{commit}")
git merge-base --is-ancestor "$commit" origin/main || fail "v$ver ($commit) is not on origin/main"
gpg --list-secret-keys "$key" >/dev/null 2>&1 || fail "no secret key $key in gpg"
[ -f "$android_jar" ] || fail "no $android_jar — install it with: sdkmanager \"platforms;$ANDROID_PLATFORM\""
if [ "$upload" = 1 ]; then
  [ -n "${SONATYPE_USERNAME:-}" ] && [ -n "${SONATYPE_PASSWORD:-}" ] ||
    fail "SONATYPE_USERNAME / SONATYPE_PASSWORD are not set (a Central Portal user token)"
fi
ssh_mac true || fail "cannot ssh to $mac without a password"
echo "== v$ver is $commit, on origin/main =="
# Kept afterwards on purpose: it holds the Mac's build log and the signed bundle.
work=$(mktemp -d)
echo "== work directory: $work =="

# --- 1. the Mac builds -----------------------------------------------------------------------
remote=$(ssh_mac 'mktemp -d /tmp/thicket-release.XXXXXX')
cleanup() { ssh_mac "rm -rf '$remote'" 2>/dev/null || true; }
trap cleanup EXIT
echo "== the Mac ($mac) builds v$ver in $remote =="
ssh_mac "git clone -q -c advice.detachedHead=false --branch v$ver $repo_url '$remote/thicket' && mkdir -p '$remote/thicket/modules/renderer-android/lib'"
scp -q -o BatchMode=yes "$android_jar" "$mac:$remote/thicket/modules/renderer-android/lib/android.jar"
# A login shell, for the PATH Homebrew's sbt is on.
ssh_mac "zsh -lc 'cd $remote/thicket && sbt --error -J-Xmx8G \"publish; platforms/publish\"'" >"$work/mac-build.log" 2>&1 ||
  { tail -30 "$work/mac-build.log" >&2; fail "the Mac's build failed; the log is $work/mac-build.log"; }
grep -E '^built ' "$work/mac-build.log" | sed 's/^/   /' || true

# --- 2. the staging comes back ---------------------------------------------------------------
# COPYFILE_DISABLE and --no-xattrs: otherwise macOS's tar adds a `._name` file beside every
# file, and they would go into the bundle (measured on the 0.2.0 rehearsal).
staging="$work/staging"
mkdir -p "$staging"
ssh_mac "cd '$remote/thicket/target/sona-staging' && COPYFILE_DISABLE=1 tar --no-xattrs -cf - ." | tar xf - -C "$staging" 2>/dev/null
cd "$staging"

# --- 3. check, sign, verify ------------------------------------------------------------------
modules=$(find . -name '*.pom' | wc -l | tr -d ' ')
[ "$modules" -gt 0 ] || fail "the Mac staged nothing"
[ -z "$(find . -name '._*' -o -name '.DS_Store')" ] || fail "macOS metadata files in the staging"
others=$(find net -mindepth 3 -maxdepth 3 -type d ! -name "$ver")
[ -z "$others" ] || fail "versions other than $ver staged: $others"
for pom in $(find . -name '*.pom'); do
  d=$(dirname "$pom"); base="${pom%.pom}"
  for f in "$base.jar" "$base-sources.jar" "$base-javadoc.jar"; do
    [ -f "$f" ] || fail "missing $(basename "$f") in $d"
  done
  for t in name description url licenses developers scm; do
    grep -q "<$t>" "$pom" || fail "$(basename "$pom") has no <$t>, which Central requires"
  done
  ! grep -q SNAPSHOT "$pom" || fail "$(basename "$pom") depends on a SNAPSHOT"
done
for f in $(find . -type f ! -name '*.md5' ! -name '*.sha1'); do
  [ "$(md5sum <"$f" | cut -c1-32)" = "$(cut -c1-32 "$f.md5")" ] &&
    [ "$(sha1sum <"$f" | cut -c1-40)" = "$(cut -c1-40 "$f.sha1")" ] || fail "checksum mismatch: $f"
done
echo "== $modules modules, every one with jar, sources, javadoc and a complete POM; checksums match =="

echo "== signing with $key (gpg may ask for the passphrase once) =="
signed=0
for f in $(find . -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.asc'); do
  gpg --batch --yes --armor --detach-sign -u "$key" "$f"
  signed=$((signed + 1))
done
for a in $(find . -name '*.asc'); do
  gpg --verify "$a" "${a%.asc}" 2>/dev/null || fail "signature does not verify: $a"
done
echo "== $signed files signed, every signature verified =="

bundle="$work/thicket-$ver-bundle.zip"
zip -q -r -X "$bundle" net
echo "== bundle: $bundle ($(du -h "$bundle" | cut -f1)) =="

if [ "$upload" != 1 ]; then
  echo "RELEASE_UPLOAD=0: stopping before the upload. Nothing was sent to Central."
  exit 0
fi

# --- 4. upload, validate, and stop -----------------------------------------------------------
auth="Authorization: Bearer $(printf '%s:%s' "$SONATYPE_USERNAME" "$SONATYPE_PASSWORD" | base64 | tr -d '\n')"
id=$(curl -sf -m 900 -X POST -H "$auth" -F "bundle=@$bundle" \
  "$portal/upload?name=net.leibman:thicket:$ver&publishingType=USER_MANAGED") || fail "the upload was refused"
echo "== uploaded: deployment $id; waiting for Central's validation =="
for _ in $(seq 1 80); do
  status=$(curl -sf -m 30 -X POST -H "$auth" "$portal/status?id=$id")
  state=$(printf '%s' "$status" | python3 -I -c 'import sys, json; print(json.load(sys.stdin)["deploymentState"])')
  case "$state" in VALIDATED | FAILED) break ;; esac
  sleep 15
done
printf '%s' "$status" | python3 -I -c '
import sys, json
d = json.load(sys.stdin)
print("== state:", d["deploymentState"], "| components:", len(d.get("purls") or []))
for k, v in (d.get("errors") or {}).items():
    print("   ", k, v)'
[ "$state" = VALIDATED ] || fail "Central did not validate deployment $id (state $state); drop it in the Portal"

cat <<EOF
PASS: v$ver is staged on Central as deployment $id, validated, NOT published.

Next, by hand:
  1. central.sonatype.com -> View Deployments -> $id: check the component list, then Publish
     (permanent), or Drop.
  2. Once repo1.maven.org has it (10-30 minutes):
       THICKET_VERSION=$ver bin/verify-getting-started.sh
       THICKET_VERSION=$ver bin/verify-getting-started-apple.sh   # on the Mac
  3. A PR moving each templates/*/project/plugins.sbt default to $ver (docs/14 §14.5 step 6).
EOF
