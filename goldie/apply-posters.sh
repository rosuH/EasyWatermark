#!/usr/bin/env bash
# Award is the v5 trust card: full-bleed, no device, title already on the art.
# No status bar — there is no bezel, so 9:41 / island would be a fake chrome.
# ID stays a live editor capture — do not overwrite it here.
set -euo pipefail
ROOT="/Users/rosu/Coding/EasyWatermark"
# shellcheck source=resolve-engine.sh
. "$(dirname "$0")/resolve-engine.sh"
resolve_goldie_engine
V5="/Users/rosu/Downloads/简单水印物料/v5-成图"

scale_poster() {
  local src="$1" dest="$2"
  mkdir -p "$(dirname "$dest")"
  python3 "$GOLDIE_HOME/scripts/pad-posters.py" "$src" "$dest" 1320 2868
}

for loc_src in "en-US:en" "zh-CN:zh"; do
  loc="${loc_src%%:*}"
  src="${loc_src##*:}"
  dest="$ROOT/goldie/out/screenshots/6.9/$loc/02-award.png"
  scale_poster "$V5/v5-${src}-awards.png" "$dest"
  echo "  award $loc"
done

# Studio live-composites from raw. Keep EN as a fallback; framed files
# are what the award tile should show after the studio patch.
scale_poster "$V5/v5-en-awards.png" "$ROOT/goldie/out/raw/iphone-6.9/award.png"
