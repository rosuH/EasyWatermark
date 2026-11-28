# Built-in sticker pack (v3)

Sources for the 12 built-in stickers shipped as
`shared/src/commonMain/composeResources/drawable/ic_sticker_<key>.webp`.
The shipped files are raster (lossless WebP, transparent, 512 px on the long edge, natural aspect
ratio) because the art uses gradients, clip paths and outlined type that a VectorDrawable cannot carry.

| Folder | Contents |
|---|---|
| `svg/` | the 12 generated SVG sources, named with the shipped resource keys |
| `tools/` | `stickers.py` (the pack) + `vec.py` (font-outlining and shape helpers) |

`svg/rights_reserved.svg` is the file the generator writes as `copyright.svg`: the key was renamed to
`rights_reserved` so iOS could keep reusing the existing `icon_builtin_copyright.png` on test devices
(see C2 in the redesign spec and ADR-0041).

## Regenerate

Needs `python3` + `fonttools`, `rsvg-convert` and `cwebp`, and the six fonts below installed in
`~/Library/Fonts` (the path `tools/vec.py` reads; nothing is vendored).

```sh
cd docs/design/sticker-pack
python3 -I tools/stickers.py out            # writes <key>.svg + pack.json
mv out/copyright.svg out/rights_reserved.svg

cd out
for f in *.svg; do
  read vw vh <<<"$(sed -n 's/.*viewBox="0 0 \([0-9.]*\) \([0-9.]*\)".*/\1 \2/p' "$f" | head -1)"
  if (( vw >= vh )); then dim=(-w 512); else dim=(-h 512); fi
  rsvg-convert "${dim[@]}" "$f" -o "${f%.svg}.png"
  cwebp -quiet -lossless -z 9 -exact -alpha_filter best "${f%.svg}.png" \
    -o "../../../../shared/src/commonMain/composeResources/drawable/ic_sticker_${f%.svg}.webp"
done
```

`-exact` keeps the colour under fully transparent pixels, so the round trip is byte-exact; check with
`dwebp` + `magick compare -metric AE`. The whole pack is ~143 KiB.

The script also emits `mine_*.svg` and `pack.json`: demo "my stickers" and data for the HTML design
prototype. Neither ships — do not copy them into `composeResources`.

## Lettering

All lettering is **outlined** into plain `<path>` data by `vec.py` (fontTools, with GPOS pair kerning),
so no font binary and no `font-family` reference ends up in the app. The faces used are OFL-licensed:

- Roboto Condensed Black — the Latin display type (`ORIGINAL`, `NO AI`, `DO NOT COPY`, `SAMPLE`, …)
- IBM Plex Mono Bold — the arc text on the `rights_reserved` seal
- Dream Han Sans CN W26 — 禁止转载 / 盗图必究
- Noto Serif SC Black — 原创作品

`tools/stickers.py` also loads IBM Plex Serif and IBM Plex Sans for the unshipped `mine_*` demo art, so
all six faces must be present before the script will import.
