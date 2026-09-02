#!/usr/bin/env bash
# Frame iPad 13" raws with the existing store-template (device=tablet),
# same headlines as goldie/shared.ts.
set -euo pipefail

ROOT="/Users/rosu/Coding/EasyWatermark"
# shellcheck source=resolve-engine.sh
. "$(dirname "$0")/resolve-engine.sh"
resolve_goldie_engine
RAW="$ROOT/goldie/out/raw/ipad-13"
OUT="$ROOT/goldie/out/screenshots/13"
TPL="$ROOT/docs/release/assets/store-template"
RENDER="$TPL/render.sh"
W=2064
H=2752

PAD="$GOLDIE_HOME/scripts/pad-posters.py"
python3 - "$RAW" "$OUT" "$TPL" "$RENDER" "$W" "$H" "$PAD" <<'PY'
import os, sys, urllib.parse, subprocess, shutil

raw, out, tpl, render, w, h, pad = sys.argv[1:]
w, h = int(w), int(h)

scenes = [
    ("work", "flat", {
        "en-US": ("Ship the work, [[keep the credit]]", "Tiled across the image. Credit remains after every repost."),
        "zh-CN": ("作品发出去，[[署名跟着走]]", "平铺覆盖全图，转载后署名仍在"),
    }),
    ("style", "flat", {
        "en-US": ("Fill, stroke, [[your style]]", "Weight, italics, outline — make the mark yours"),
        "zh-CN": ("笔画级的[[样式控制]]", "填充、描边、粗体斜体——你的水印，你的风格"),
    }),
    ("idcard", "flat", {
        "en-US": ("Uploading an ID? [[Say why]]", "“For this application only” — useless anywhere else"),
        "zh-CN": ("办证上传？[[写明用途]]", "「仅供申办之用」——被挪用也无处可用"),
    }),
    ("layout", "flat", {
        "en-US": ("Density and angle, [[easy to adjust]]", "Spacing and angle sliders, live on your photo"),
        "zh-CN": ("密度角度，[[随手可调]]", "水平垂直间距实时预览，铺满每一个角落"),
    }),
    ("export", "flat", {
        "en-US": ("Export on [[your terms]]", "JPEG or PNG, a quality dial, your folder, in batch"),
        "zh-CN": ("格式质量，[[由你决定]]", "JPEG / PNG、质量滑杆、自选文件夹，批量导出"),
    }),
]

order = {
    "work": "01-work",
    "style": "02-style",
    "idcard": "03-idcard",
    "layout": "04-layout",
    "export": "05-export",
}

# Award poster is device-agnostic; scale the existing v5 card into the 13" slot.
v5 = os.path.expanduser("~/Downloads/简单水印物料/v5-成图")
posters = {
    "en-US": os.path.join(v5, "v5-en-awards.png"),
    "zh-CN": os.path.join(v5, "v5-zh-awards.png"),
}

for loc in ("en-US", "zh-CN"):
    dest_dir = os.path.join(out, loc)
    os.makedirs(dest_dir, exist_ok=True)
    lang = "en" if loc == "en-US" else "zh"
    for sid, scene, copy in scenes:
        src = os.path.join(raw, loc, f"{sid}.png")
        if not os.path.isfile(src):
            raise SystemExit(f"missing raw {src}")
        title, sub = copy[loc]
        shot_rel = os.path.relpath(src, tpl)
        params = urllib.parse.urlencode({
            "shot": shot_rel,
            "title": title,
            "sub": sub,
            "lang": lang,
            "device": "tablet",
            "scene": scene,
        })
        dest = os.path.join(dest_dir, f"{order[sid]}.png")
        subprocess.check_call([render, dest, str(w), str(h), params])
    poster = posters[loc]
    if os.path.isfile(poster):
        dest = os.path.join(dest_dir, "00-award.png")
        subprocess.check_call([sys.executable, pad, poster, dest, str(w), str(h)])

print("framed", out)
PY
