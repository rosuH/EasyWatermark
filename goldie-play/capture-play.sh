#!/usr/bin/env bash
# Capture Play Store stills on a 1080×1920 Android emulator.
# Does not touch the iOS simulator. Award is a scaled v5 poster (no status bar).
set -euo pipefail

ROOT="/Users/rosu/Coding/EasyWatermark"
# shellcheck source=../goldie/resolve-engine.sh
. "$(dirname "$0")/../goldie/resolve-engine.sh"
resolve_goldie_engine
RAW="$ROOT/goldie-play/out/raw"
SAMPLES="/Users/rosu/Downloads/简单水印物料"
PKG="${PLAY_PKG:-me.rosuh.easywatermark.debug}"
ACTIVITY="me.rosuh.easywatermark.ui.MainActivity"
AVD="${PLAY_AVD:-Pixel_9_Pro_XL}"
EXPECT_W=1080
EXPECT_H=1920

adb_bin() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return
  fi
  for d in "$ANDROID_HOME" "$HOME/Library/Android/sdk"; do
    if [[ -n "${d:-}" && -x "$d/platform-tools/adb" ]]; then
      echo "$d/platform-tools/adb"
      return
    fi
  done
  echo "adb not found" >&2
  exit 1
}

ADB="$(adb_bin)"

wait_for_device() {
  local serial="$1"
  "$ADB" -s "$serial" wait-for-device
  for _ in $(seq 1 60); do
    if "$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
      return 0
    fi
    sleep 2
  done
  echo "FAIL emulator did not finish booting" >&2
  exit 1
}

ensure_emulator() {
  local serial
  serial="$("$ADB" devices | awk '/emulator-/{print $1; exit}')"
  if [[ -n "${serial:-}" ]]; then
    echo "$serial"
    return
  fi
  echo "  starting $AVD headless (iPhone simulator stays up)" >&2
  local emu
  emu="${ANDROID_HOME:-$HOME/Library/Android/sdk}/emulator/emulator"
  "$emu" -avd "$AVD" -no-snapshot-load -no-snapshot-save \
    -gpu swiftshader_indirect -no-window -memory 4096 \
    -netdelay none -netspeed full >/tmp/goldie-play-emu.log 2>&1 &
  for _ in $(seq 1 90); do
    serial="$("$ADB" devices | awk '/emulator-/{print $1; exit}')"
    if [[ -n "${serial:-}" ]]; then
      wait_for_device "$serial"
      echo "$serial"
      return
    fi
    sleep 2
  done
  echo "FAIL could not start $AVD" >&2
  exit 1
}

pin_size() {
  "$ADB" -s "$SERIAL" shell wm size "${EXPECT_W}x${EXPECT_H}"
  "$ADB" -s "$SERIAL" shell wm density 420
}

demo_bar() {
  "$ADB" -s "$SERIAL" shell settings put global sysui_demo_allowed 1 >/dev/null
  "$ADB" -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
  "$ADB" -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
  "$ADB" -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
  "$ADB" -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
  "$ADB" -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype 5g -e level 4 >/dev/null
  "$ADB" -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
}

push_samples() {
  "$ADB" -s "$SERIAL" shell mkdir -p /sdcard/Pictures/EwmStore
  "$ADB" -s "$SERIAL" push "$SAMPLES/sample-id-card.png" /sdcard/Pictures/EwmStore/ewm_store_0_id.png >/dev/null
  "$ADB" -s "$SERIAL" push "$SAMPLES/1787384879218.jpg" /sdcard/Pictures/EwmStore/ewm_store_1_photo.jpg >/dev/null
  "$ADB" -s "$SERIAL" push "$SAMPLES/1000019783.jpeg" /sdcard/Pictures/EwmStore/ewm_store_2_fuji.jpeg >/dev/null
  for f in ewm_store_0_id.png ewm_store_1_photo.jpg ewm_store_2_fuji.jpeg; do
    "$ADB" -s "$SERIAL" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d "file:///sdcard/Pictures/EwmStore/$f" >/dev/null || true
  done
  sleep 1
}

grant_perms() {
  "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.READ_MEDIA_IMAGES >/dev/null || true
  "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE >/dev/null || true
  "$ADB" -s "$SERIAL" shell appops set "$PKG" READ_MEDIA_IMAGES allow >/dev/null || true
}

set_locale() {
  local loc="$1"
  "$ADB" -s "$SERIAL" shell cmd locale set-app-locales "$PKG" --locales "$loc" >/dev/null || \
    "$ADB" -s "$SERIAL" shell cmd locale set-app-locales "$PKG" "$loc" >/dev/null || true
}

launch_seed() {
  local scene="$1"
  "$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null
  sleep 0.4
  "$ADB" -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY" --es storeSeedScene "$scene" >/dev/null
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

screenshot_scene() {
  local locale="$1"
  local id="$2"
  local scene="$3"
  local wait_s="$4"
  local dest="$RAW/$locale/${id}.png"
  echo "  $locale $id (seed=$scene)"
  mkdir -p "$(dirname "$dest")"
  launch_seed "$scene"
  sleep "$wait_s"
  demo_bar
  sleep 0.6
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$dest"
  assert_size "$dest"
}

SERIAL="$(ensure_emulator)"
echo "> Play capture $SERIAL ($AVD)"
pin_size
"$ADB" -s "$SERIAL" shell cmd uimode night yes >/dev/null || true

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "FAIL $APK missing — build :app:assembleDebug first" >&2
  exit 1
fi
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
grant_perms
push_samples

for loc in en-US zh-CN; do
  set_locale "$loc"
  screenshot_scene "$loc" work photo 13
  screenshot_scene "$loc" style style 13
  screenshot_scene "$loc" idcard idcard 13
  screenshot_scene "$loc" color color 13
  screenshot_scene "$loc" layout layout 13
  screenshot_scene "$loc" template templates 12
  screenshot_scene "$loc" export export 12
done

python3 "$GOLDIE_HOME/scripts/frame-play.py"
echo "capture-play done"
