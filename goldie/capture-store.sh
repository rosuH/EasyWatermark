#!/usr/bin/env bash
# Capture App Store stills + preview clips via simulator launch arguments.
# Launch the app and wait until the scene is ready BEFORE recording, so
# clips never start on SpringBoard / the home screen.
set -euo pipefail

UDID="${UDID:-2593E468-6284-44B0-98CA-EFB336747E7D}"
BUNDLE="me.rosuh.easywatermark.ios"
APP="/Users/rosu/Coding/EasyWatermark/build/ios_goldie_dd/Build/Products/Release-iphonesimulator/iosApp.app"
ROOT="/Users/rosu/Coding/EasyWatermark"
# shellcheck source=resolve-engine.sh
. "$(dirname "$0")/resolve-engine.sh"
resolve_goldie_engine
RAW="$ROOT/goldie/out/raw/iphone-6.9"
EXPECT_W=1320
EXPECT_H=2868

pin_locale() {
  xcrun simctl spawn "$UDID" defaults write -g AppleLanguages -array en
  xcrun simctl spawn "$UDID" defaults write -g AppleLocale en_US
}

pin_status_bar() {
  xcrun simctl status_bar "$UDID" override \
    --time "9:41" \
    --batteryState charged \
    --batteryLevel 100 \
    --wifiMode active \
    --wifiBars 3 \
    --cellularMode active \
    --cellularBars 4 \
    --dataNetwork 5g
}

assert_size() {
  local file="$1"
  local w h
  w=$(sips -g pixelWidth "$file" | awk '/pixelWidth/ {print $2}')
  h=$(sips -g pixelHeight "$file" | awk '/pixelHeight/ {print $2}')
  if [[ "$w" != "$EXPECT_W" || "$h" != "$EXPECT_H" ]]; then
    echo "FAIL $file is ${w}x${h}, expected ${EXPECT_W}x${EXPECT_H}" >&2
    exit 1
  fi
}

launch_seed() {
  local scene="$1"
  xcrun simctl terminate "$UDID" "$BUNDLE" >/dev/null 2>&1 || true
  sleep 0.4
  xcrun simctl launch "$UDID" "$BUNDLE" -storeSeedScene "$scene" >/dev/null
}

screenshot_scene() {
  local id="$1"
  local scene="$2"
  local wait_s="$3"
  echo "  screenshot $id (scene=$scene)"
  launch_seed "$scene"
  sleep "$wait_s"
  pin_status_bar
  sleep 0.8
  pin_status_bar
  sleep 0.4
  xcrun simctl io "$UDID" screenshot "$RAW/${id}.png" >/dev/null
  assert_size "$RAW/${id}.png"
}

# Launch and settle FIRST, then record WHILE driving chrome. A hold of a
# finished scene is a still; the store preview must show the UI changing.
record_live_tour() {
  local file="$RAW/preview-tour.mp4"
  local argent="${GOLDIE_ARGENT_BIN:-/Users/rosu/n/lib/node_modules/goldie/node_modules/@swmansion/argent/dist/cli.js}"
  echo "  preview tour (seed photo, then live Style / color / Layout / Save)"
  launch_seed photo
  sleep 13
  pin_status_bar
  node "$argent" run screen-recording-start --udid "$UDID" --no-trimStatic --no-showTouches --timeLimitSeconds 30
  (
    cd "$ROOT"
    node "$argent" flow run store-preview-tour --device "$UDID"
  )
  local json
  json=$(node "$argent" run screen-recording-stop --udid "$UDID" --json)
  python3 -c 'import json,sys,shutil; shutil.copy(json.loads(sys.argv[1])["video"], sys.argv[2])' "$json" "$file"
  if [[ ! -s "$file" ]]; then
    echo "FAIL preview clip missing: $file" >&2
    exit 1
  fi
}

mkdir -p "$RAW"
pin_locale
xcrun simctl ui "$UDID" appearance dark
xcrun simctl install "$UDID" "$APP"
pin_status_bar

V5="/Users/rosu/Downloads/简单水印物料/v5-成图"

# Finished v5 posters (no simulator). EN raw is what studio live-composites.
scale_poster() {
  local src="$1"
  local dest="$2"
  python3 "$GOLDIE_HOME/scripts/pad-posters.py" "$src" "$dest" "$EXPECT_W" "$EXPECT_H"
  assert_size "$dest"
}

echo "> iPhone 17 Pro Max ($UDID)"
screenshot_scene work photo 12
screenshot_scene style style 13
echo "  poster award"
scale_poster "$V5/v5-en-awards.png" "$RAW/award.png"
"$ROOT/goldie/apply-posters.sh"
screenshot_scene idcard idcard 13
screenshot_scene color color 13
screenshot_scene layout layout 13
screenshot_scene template templates 12
screenshot_scene export export 12

record_live_tour

"$GOLDIE_HOME/scripts/remux-preview.sh"

python3 - <<PY
import json, datetime, subprocess, os
raw = "$RAW"
def dur(path):
    out = subprocess.check_output([
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=nw=1:nk=1", path
    ], text=True).strip()
    return float(out)
ids = ["work", "award", "style", "idcard", "color", "layout", "template", "export"]
preview_ids = ["tour"]
manifest = {
    "device": "iphone-6.9",
    "udid": "$UDID",
    "capturedAt": datetime.datetime.utcnow().isoformat() + "Z",
    "screenshots": [{"sceneId": i, "file": os.path.join(raw, f"{i}.png")} for i in ids],
    "preview": {
        "sceneId": "preview",
        "clips": [
            {"segmentId": i, "file": os.path.join(raw, f"preview-{i}.mp4"), "durationSeconds": dur(os.path.join(raw, f"preview-{i}.mp4"))}
            for i in preview_ids
        ],
    },
}
total = sum(c["durationSeconds"] for c in manifest["preview"]["clips"])
print(f"  preview total {total:.1f}s")
if total < 15 or total > 30:
    raise SystemExit(f"preview total {total:.1f}s is outside 15-30s")
with open(os.path.join(raw, "manifest.json"), "w") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")
print("wrote", os.path.join(raw, "manifest.json"))
PY

echo "capture-store done"
