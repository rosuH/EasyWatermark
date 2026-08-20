#!/bin/bash
# Render one framed store screenshot from frame.html via headless Chrome.
#
# Usage:
#   ./render.sh OUT.png WIDTH HEIGHT "shot=...&title=...&sub=...&accent=...&lang=..."
#
# Params are raw URL-query text; percent-encode values that contain & or =.
# Screenshot paths in `shot` are resolved relative to this directory.
set -euo pipefail

OUT="$1"; W="$2"; H="$3"; PARAMS="$4"
DIR="$(cd "$(dirname "$0")" && pwd)"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

"$CHROME" --headless --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 \
  --window-size="$W,$H" \
  --screenshot="$OUT" \
  --virtual-time-budget=4000 \
  "file://$DIR/frame.html?$PARAMS" 2>/dev/null

echo "$OUT ($W x $H)"
