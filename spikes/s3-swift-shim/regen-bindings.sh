#!/usr/bin/env bash
# Regenerate the Scala bindings for shim/include/scalaui_shim.h.
#
# Deliberately not wired into build.sbt. sn-bindgen 0.4.5's prebuilt macOS binary is
# dynamically linked against Homebrew's llvm@17 *specifically*:
#
#   dyld: Library not loaded: /opt/homebrew/opt/llvm@17/lib/libclang.dylib
#
# Rather than install a ~1.5 GB Homebrew LLVM to match, point dyld at the libclang that
# already ships with Xcode — verified to work, and it keeps the toolchain to what
# MAC-SETUP.md already requires. Keeping generation out of the build also means a plain
# `sbt compile` works for anyone who has not done this.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=0.4.5
ARTIFACT="bindgen_native0.5_3-$VERSION-aarch64-osx.exe"
URL="https://repo1.maven.org/maven2/com/indoorvivants/bindgen_native0.5_3/$VERSION/$ARTIFACT"

# Reuse the coursier-cached copy if the sbt plugin already pulled it; otherwise fetch it.
BINDGEN=$(find "$HOME/Library/Caches/Coursier" -name "$ARTIFACT" 2>/dev/null | head -1)
if [ -z "$BINDGEN" ]; then
  BINDGEN="${TMPDIR:-/tmp}/$ARTIFACT"
  [ -f "$BINDGEN" ] || { echo "downloading sn-bindgen $VERSION…"; curl -fsSL "$URL" -o "$BINDGEN"; }
  chmod +x "$BINDGEN"
fi

XCODE_CLANG_LIB=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib
OUT=scala-lib/src/main/scala/scalaui/s3/generated.scala

DYLD_FALLBACK_LIBRARY_PATH="$XCODE_CLANG_LIB" "$BINDGEN" \
  --header shim/include/scalaui_shim.h \
  --package scalaui.s3.generated \
  --c-import scalaui_shim.h \
  --scala --flavour scala-native05 \
  --clang -Ishim/include \
  > "$OUT"

# NOT annotating the externs @blocking, deliberately.
#
# Scala Native's stop-the-world waits for Managed threads to reach a safepoint, and a
# thread inside an extern call is Managed but in native code. @blocking makes that
# transition explicit and the GC's own error text suggests it. Measured both ways here:
# with @blocking, sui_label_set_text costs 444 ns/call; without, 216 ns. The 400-round
# soak (a background thread forcing GC while the main thread services UI posts) completes
# cleanly either way, because UIKit calls return far inside the 10 s safepoint timeout.
#
# So @blocking belongs on the calls that can genuinely block for a long time — file and
# network I/O, modal presentation — not on every property setter. Blanket-annotating
# doubles the cost of the most frequent operation in the framework to buy nothing.
# What *is* required is GcState: marking the thread Unmanaged when Scala returns to the
# host run loop. Without that the app reliably aborts. See REPORT.md.

# sn-bindgen emits Scala 3 *indentation* syntax, which every build rejects via -no-indent
# (docs/decisions.md). After regenerating, convert it back to braces by temporarily adding
# "-rewrite" alongside "-no-indent" in build.sbt, compiling once, then removing "-rewrite".
# The compiler handles all of it here; there are no "fewer braces" colon-form sites in
# sn-bindgen's output. Then run scalafmt against the repo-root .scalafmt.conf, or the
# checked-in copy will differ from a freshly generated one on every unrelated diff.

echo "regenerated $(wc -l < "$OUT" | tr -d ' ') lines into $OUT"
