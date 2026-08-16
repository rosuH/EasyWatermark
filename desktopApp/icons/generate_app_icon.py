#!/usr/bin/env python3
"""Generate Desktop (CMP/jpackage) app icons from the iOS marketing icon.

The brand composition is the iOS App Icon: black field, centered yellow slash
plate. Do not explode that plate to fill the canvas.

macOS Dock does not mask a jpackage `.icns` or an AWT `Window(icon)` bitmap.
Neighboring apps ship Apple's 1024 canvas with an 824pt squircle (100px inset)
plus a light drop shadow and transparent corners. We drop the iOS 1024 artwork
into that plate unchanged.

Windows/Linux keep the same square artwork (those shells do not use the Mac
template).
"""

from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent
IOS_MASTER = (
    ROOT.parents[1]
    / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
)
MASTER = ROOT / "master-1024.png"
MACOS_ICNS = ROOT / "macos" / "EasyWatermark.icns"
WINDOWS_ICO = ROOT / "windows" / "EasyWatermark.ico"
LINUX_PNG = ROOT / "linux" / "EasyWatermark-512.png"
WINDOW_PNG = (
    ROOT.parents[1]
    / "shared/src/commonMain/composeResources/drawable/ic_app_icon_window.png"
)

MACOS_CANVAS = 1024
MACOS_INSET = 100  # 824pt squircle
SQUIRCLE_N = 5.1
SHADOW_DY = 12
SHADOW_BLUR = 16
SHADOW_OPACITY = 0.22


def load_artwork() -> Image.Image:
    if not IOS_MASTER.is_file():
        raise FileNotFoundError(f"iOS app icon missing: {IOS_MASTER}")
    return Image.open(IOS_MASTER).convert("RGBA")


def _squircle_body(canvas: int = MACOS_CANVAS) -> np.ndarray:
    inset = int(round(MACOS_INSET * canvas / MACOS_CANVAS))
    yy, xx = np.mgrid[0:canvas, 0:canvas]
    left = inset
    right = canvas - inset
    cx = cy = (left + right - 1) / 2.0
    radius = (right - left) / 2.0
    xf = (xx - cx) / radius
    yf = (yy - cy) / radius
    v = np.abs(xf) ** SQUIRCLE_N + np.abs(yf) ** SQUIRCLE_N
    return np.clip((1.02 - v) / (1.02 - 0.98), 0.0, 1.0)


def apply_macos_plate(artwork: Image.Image, canvas: int = MACOS_CANVAS) -> Image.Image:
    """Keep the iOS black field + centered mark; only the outer Mac silhouette changes."""
    artwork = artwork.convert("RGBA").resize((canvas, canvas), Image.Resampling.LANCZOS)
    inset = int(round(MACOS_INSET * canvas / MACOS_CANVAS))
    inner = canvas - 2 * inset
    mark = artwork.resize((inner, inner), Image.Resampling.LANCZOS)
    rgb = Image.new("RGB", (canvas, canvas), (0, 0, 0))
    rgb.paste(mark.convert("RGB"), (inset, inset))
    body = _squircle_body(canvas)
    body_u8 = Image.fromarray((body * 255).astype(np.uint8), "L")
    dy = int(round(SHADOW_DY * canvas / MACOS_CANVAS))
    blur = max(1, int(round(SHADOW_BLUR * canvas / MACOS_CANVAS)))
    shadow = Image.new("L", (canvas, canvas), 0)
    shadow.paste(body_u8, (0, dy))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    shadow_a = np.array(shadow, dtype=np.float32) * SHADOW_OPACITY
    alpha = np.where(body > 0.5, np.maximum(body * 255.0, shadow_a), shadow_a)
    alpha_img = Image.fromarray(alpha.astype(np.uint8), "L")
    out = rgb.convert("RGBA")
    out.putalpha(alpha_img)
    return out


def _save_png(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="PNG")


def _iconset(master: Image.Image, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    names = {
        "icon_16x16.png": 16,
        "icon_16x16@2x.png": 32,
        "icon_32x32.png": 32,
        "icon_32x32@2x.png": 64,
        "icon_128x128.png": 128,
        "icon_128x128@2x.png": 256,
        "icon_256x256.png": 256,
        "icon_256x256@2x.png": 512,
        "icon_512x512.png": 512,
        "icon_512x512@2x.png": 1024,
    }
    with tempfile.TemporaryDirectory() as tmp:
        iconset = Path(tmp) / "EasyWatermark.iconset"
        iconset.mkdir()
        for name, px in names.items():
            master.resize((px, px), Image.Resampling.LANCZOS).save(
                iconset / name, format="PNG"
            )
        subprocess.check_call(
            ["iconutil", "--convert", "icns", "--output", str(dest), str(iconset)]
        )


def _windows_ico(artwork: Image.Image, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    sizes = (16, 24, 32, 48, 256)
    with tempfile.TemporaryDirectory() as tmp:
        tmp_p = Path(tmp)
        files = []
        for px in sizes:
            img = artwork.resize((px, px), Image.Resampling.LANCZOS)
            p = tmp_p / f"{px}.png"
            img.save(p, format="PNG")
            files.append(p)
        subprocess.check_call(
            [
                "magick",
                *[str(f) for f in files],
                "-background",
                "none",
                str(dest),
            ]
        )


def main() -> None:
    artwork = load_artwork()
    macos = apply_macos_plate(artwork)
    _save_png(macos, MASTER)
    _iconset(macos, MACOS_ICNS)
    _windows_ico(artwork, WINDOWS_ICO)
    _save_png(artwork.resize((512, 512), Image.Resampling.LANCZOS), LINUX_PNG)
    _save_png(macos.resize((128, 128), Image.Resampling.LANCZOS), WINDOW_PNG)
    print("wrote", MASTER)
    print("wrote", MACOS_ICNS, MACOS_ICNS.stat().st_size)
    print("wrote", WINDOWS_ICO, WINDOWS_ICO.stat().st_size)
    print("wrote", LINUX_PNG)
    print("wrote", WINDOW_PNG)


if __name__ == "__main__":
    main()
