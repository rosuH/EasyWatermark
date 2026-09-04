#!/usr/bin/env python3
"""Stamp 9:41 / island / battery onto a poster, using the poster's own top color."""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

W, H = 1320, 2868
BAR = 140
CHROME = Path("/Users/rosu/Coding/EasyWatermark/goldie/out/raw/iphone-6.9/style.png")


def dist(a: tuple[int, int, int], b: tuple[int, int, int]) -> int:
    return abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])


def stamp(src: Path, dest: Path) -> None:
    poster = Image.open(src).convert("RGB").resize((W, H), Image.Resampling.LANCZOS)
    chrome = Image.open(CHROME).convert("RGB")
    if chrome.size != (W, H):
        chrome = chrome.resize((W, H), Image.Resampling.LANCZOS)

    # Stretch the poster's own top pixels so the bar matches the card, not the editor chrome.
    bar_bg = poster.crop((0, 0, W, 12)).resize((W, BAR), Image.Resampling.BILINEAR)
    chrome_bar = chrome.crop((0, 0, W, BAR))
    # Editor chrome fill — anything close to this is background, not an icon.
    samples = [
        chrome_bar.getpixel((x, y))
        for y in (2, 8, 16)
        for x in (4, 20, W - 20, W // 2)
    ]
    samples.sort()
    fill = samples[len(samples) // 2]

    out_bar = bar_bg.copy()
    for y in range(BAR):
        for x in range(W):
            p = chrome_bar.getpixel((x, y))
            if dist(p, fill) > 40:
                out_bar.putpixel((x, y), p)

    body = poster.crop((0, 0, W, H - BAR))
    out = Image.new("RGB", (W, H))
    out.paste(out_bar, (0, 0))
    out.paste(body, (0, BAR))
    dest.parent.mkdir(parents=True, exist_ok=True)
    out.save(dest, "PNG")
    print(f"  status bar {src.name} → {dest.name}")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: stamp-statusbar.py <src.png> <dest.png>")
    stamp(Path(sys.argv[1]), Path(sys.argv[2]))


if __name__ == "__main__":
    main()
