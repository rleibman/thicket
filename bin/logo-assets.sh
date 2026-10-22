#!/usr/bin/env bash
# Regenerates every logo asset from `thicket-logo.svg`, the one hand-edited source.
#
#   docs/assets/thicket-logo.svg      cropped to the drawing, text as outlines — what an
#                                     app shows where the platform draws SVG (GTK)
#   docs/assets/thicket-logo.png      560 px wide on white — for the platforms whose image
#   docs/assets/thicket-logo-app.png  300 px wide, transparent   decoders take raster only
#   examples/todo-android/app/src/main/assets/thicket-logo.png   a copy of the -app one
#
# The text becomes outlines because the source names fonts (Inter first) that a machine
# drawing the asset may not have; outlines draw the same everywhere. Needs Inkscape 1.x.
set -euo pipefail

cd "$(dirname "$0")/.."

src=thicket-logo.svg
out=docs/assets

inkscape "$src" --export-area-drawing --export-text-to-path \
  --export-plain-svg --export-type=svg --export-filename="$out/thicket-logo.svg"
# Inkscape crops to the ink exactly, so the tagline and leaves touch the edge. Pad the
# viewBox by 4% of the width on every side; the PNGs below inherit it.
python3 - "$out/thicket-logo.svg" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p).read()
x, y, w, h = map(float, re.search(r'viewBox="([^"]+)"', s).group(1).split())
m = round(w * 0.04, 3)
w2, h2 = round(w + 2 * m, 3), round(h + 2 * m, 3)
s = re.sub(r'viewBox="[^"]+"', f'viewBox="{round(x - m, 3)} {round(y - m, 3)} {w2} {h2}"', s, count=1)
s = re.sub(r'(<svg[^>]*?)width="[^"]+"', rf'\g<1>width="{w2}"', s, count=1, flags=re.S)
s = re.sub(r'(<svg[^>]*?)height="[^"]+"', rf'\g<1>height="{h2}"', s, count=1, flags=re.S)
open(p, "w").write(s)
PY
inkscape "$out/thicket-logo.svg" --export-type=png --export-width=560 \
  --export-background=white --export-background-opacity=1 --export-filename="$out/thicket-logo.png"
inkscape "$out/thicket-logo.svg" --export-type=png --export-width=300 \
  --export-filename="$out/thicket-logo-app.png"
cp "$out/thicket-logo-app.png" examples/todo-android/app/src/main/assets/thicket-logo.png

file "$out"/thicket-logo* examples/todo-android/app/src/main/assets/thicket-logo.png
