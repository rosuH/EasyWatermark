#!/usr/bin/env python3
"""EasyWatermark built-in sticker pack v3 — pure vector (outlined OFL type), no filters.

Run: python3 -I stickers.py OUT_DIR   -> writes <key>.svg + pack.json
"""
import json, math, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vec import Font, n, rrect, circle, ring, poly, ngon, sparkle, p, stroke, svg

INK = "#15150D"
WHITE = "#FFFFFF"
YEL = "#FFDE32"
RED = "#FF4433"
BLUE = "#1D9BF0"
VERM = "#D8301F"
TT_CYAN = "#25F4EE"
TT_RED = "#FE2C55"

RC = Font("Roboto_Condensed-Black.ttf")
MONO = Font("IBMPlexMono-Bold.otf")
SERIF_I = Font("IBMPlexSerif-BoldItalic.otf")
SANS = Font("IBMPlexSans-Bold.otf")
HAN = Font("DreamHanSansCN-W26.ttf")
SONG = Font("NotoSerifSC-Black.ttf")


def ellipse(cx, cy, rx, ry):
    return f"M{n(cx-rx)} {n(cy)}A{n(rx)} {n(ry)} 0 1 0 {n(cx+rx)} {n(cy)}A{n(rx)} {n(ry)} 0 1 0 {n(cx-rx)} {n(cy)}Z"


def han_cell(font, ch, size, cx, cy):
    """One CJK glyph centred on (cx, cy) using the ideographic em box."""
    d, _ = font.path(ch, size, cx - size / 2, cy + size * 0.38)
    return d


def grid2x2(font, text, size, cx, cy, gap):
    step = size / 2 + gap / 2
    cells = [(-step, -step), (step, -step), (-step, step), (step, step)]
    return " ".join(han_cell(font, ch, size, cx + dx, cy + dy) for ch, (dx, dy) in zip(text, cells))


# ---------------------------------------------------------------- stickers
def verified():
    cx = cy = 256
    lobes, d, rho, core = 12, 178, 56, 190

    def seal(extra, col):
        out = p(circle(cx, cy, core + extra), col)
        for i in range(lobes):
            a = 2 * math.pi * i / lobes
            out += p(circle(cx + d * math.cos(a), cy + d * math.sin(a), rho + extra), col)
        return out

    body = seal(16, WHITE) + seal(0, BLUE)
    body += stroke("M164 264L228 326L350 194", WHITE, 46)
    return svg(512, 512, body)


def original():
    cap, tracking = 100, 16
    size = RC.size_for_cap(cap)
    tw = RC.width("ORIGINAL", size, tracking)
    sp, gap, padx, H, m = 42, 22, 54, 200, 16
    W = 2 * sp + gap + tw + 2 * padx
    x0, y0 = m, m
    gx1, gx2 = x0 + padx, x0 + W - padx
    defs = (
        f'<linearGradient id="ig" gradientUnits="userSpaceOnUse" x1="{n(gx1)}" y1="0" x2="{n(gx2)}" y2="0">'
        '<stop offset="0" stop-color="#F58529"/><stop offset="0.42" stop-color="#DD2A7B"/>'
        '<stop offset="0.74" stop-color="#8134AF"/><stop offset="1" stop-color="#515BD4"/></linearGradient>'
    )
    body = p(rrect(x0, y0, W, H, H / 2), WHITE)
    body += p(sparkle(gx1 + sp, y0 + H / 2, sp, 0.22), "url(#ig)")
    d, _ = RC.path("ORIGINAL", size, gx1 + 2 * sp + gap, y0 + H / 2 + cap / 2, tracking=tracking)
    body += p(d, "url(#ig)")
    return svg(W + 2 * m, H + 2 * m, body, defs)


def no_ai():
    cx = cy = 256
    body = p(ngon(cx, cy, 252, 8, 22.5), WHITE)
    body += p(ngon(cx, cy, 236, 8, 22.5), RED)
    body += f'<path d="{ngon(cx, cy, 208, 8, 22.5)}" fill="none" stroke="{WHITE}" stroke-width="7" stroke-linejoin="miter"/>'
    s1 = RC.fit("NO AI", 300, 124, tracking=6)
    cap1 = s1 * RC.cap / RC.upm
    s2 = RC.fit("TRAINING", 252, 40, tracking=150)
    cap2 = s2 * RC.cap / RC.upm
    gap = 30
    top = cy - (cap1 + gap + cap2) / 2
    d1, _ = RC.path("NO AI", s1, cx, top + cap1, anchor="middle", tracking=6)
    d2, _ = RC.path("TRAINING", s2, cx, top + cap1 + gap + cap2, anchor="middle", tracking=150)
    body += p(d1, WHITE) + p(d2, WHITE)
    return svg(512, 512, body)


def no_repost():
    m, W, H = 16, 448, 340
    x0, y0 = m, m
    body = p(rrect(x0 - 0, y0 - 0, W, H, 54), WHITE)
    body += p(rrect(x0 + 14, y0 + 14, W - 28, H - 28, 42), "#0B0B0B")
    size = RC.fit("REPOST", 316, 104, tracking=4)
    cap = size * RC.cap / RC.upm
    left = x0 + (W - RC.width("REPOST", size, 4)) / 2
    top = y0 + (H - (cap + 40 + 30 + cap)) / 2
    # line 1: NO in a red tab
    wno = RC.width("NO", size, 4)
    body += p(rrect(left - 4, top, wno + 44, cap + 40, 14), TT_RED)
    d, _ = RC.path("NO", size, left + 18, top + 20 + cap, tracking=4)
    body += p(d, WHITE)
    # line 2: REPOST with split-channel offset
    base = top + cap + 40 + 30 + cap
    d, _ = RC.path("REPOST", size, left, base, tracking=4)
    body += f'<path d="{d}" fill="{TT_CYAN}" transform="translate(-7 -5)"/>'
    body += f'<path d="{d}" fill="{TT_RED}" transform="translate(7 5)"/>'
    body += p(d, WHITE)
    return svg(W + 2 * m, H + 2 * m, body)


def do_not_copy():
    VW, VH, W, H, r = 620, 400, 548, 296, 28
    x0, y0 = (VW - W) / 2, (VH - H) / 2
    band = 62
    defs = f'<clipPath id="c"><path d="{rrect(x0, y0, W, H, r)}"/></clipPath>'
    g = p(rrect(x0 - 15, y0 - 15, W + 30, H + 30, r + 13), WHITE)
    inner = p(rrect(x0, y0, W, H, r), YEL)
    sw, step = 34, 68
    k = -W
    while k < W + H:
        xa = x0 + k
        inner += p(poly([(xa, y0 + H), (xa + sw, y0 + H), (xa + sw + H, y0), (xa + H, y0)]), INK)
        k += step
    inner += p(f"M{n(x0)} {n(y0+band)}H{n(x0+W)}V{n(y0+H-band)}H{n(x0)}Z", INK)
    g += f'<g clip-path="url(#c)">{inner}</g>'
    size = RC.fit("DO NOT COPY", W - 84, 92, tracking=14)
    cap = size * RC.cap / RC.upm
    d, _ = RC.path("DO NOT COPY", size, VW / 2, VH / 2 + cap / 2, anchor="middle", tracking=14)
    g += p(d, YEL)
    return svg(VW, VH, f'<g transform="rotate(-6 {n(VW/2)} {n(VH/2)})">{g}</g>', defs)


def copyright_seal():
    cx = cy = 256
    body = p(circle(cx, cy, 252), WHITE) + p(circle(cx, cy, 236), INK)
    size = MONO.size_for_cap(27)
    txt = "ALL RIGHTS RESERVED • ALL RIGHTS RESERVED • "
    body += p(MONO.arc(txt, size, cx, cy, 185, start_deg=-4, span_deg=360), WHITE)
    body += p(ring(cx, cy, 168, 165), WHITE, 'fill-opacity="0.35"')
    body += p(ring(cx, cy, 124, 100), YEL)
    r, a = 50, math.radians(42)
    x1, y1 = cx + r * math.cos(-a), cy + r * math.sin(-a)
    x2, y2 = cx + r * math.cos(a), cy + r * math.sin(a)
    body += stroke(f"M{n(x1)} {n(y1)}A{n(r)} {n(r)} 0 1 0 {n(x2)} {n(y2)}", YEL, 25, 'stroke-linecap="butt"')
    return svg(512, 512, body)


def sample():
    VW, VH, W, H = 620, 390, 508, 232
    x0, y0 = (VW - W) / 2, (VH - H) / 2
    g = p(rrect(x0, y0, W, H, 20), WHITE)
    g += f'<path d="{rrect(x0+17, y0+17, W-34, H-34, 9)}" fill="none" stroke="{RED}" stroke-width="12"/>'
    g += f'<path d="{rrect(x0+35, y0+35, W-70, H-70, 4)}" fill="none" stroke="{RED}" stroke-width="3.5"/>'
    size = RC.fit("SAMPLE", W - 116, 100, tracking=90)
    cap = size * RC.cap / RC.upm
    d, _ = RC.path("SAMPLE", size, VW / 2, VH / 2 + cap / 2, anchor="middle", tracking=90)
    g += p(d, RED)
    return svg(VW, VH, f'<g transform="rotate(-8 {n(VW/2)} {n(VH/2)})">{g}</g>')


def private():
    sh = "M168 262V178A88 88 0 0 1 344 178V262"
    body = stroke(sh, WHITE, 76, 'stroke-linecap="butt"')
    body += p(rrect(72, 214, 368, 284, 64), WHITE)
    body += stroke(sh, INK, 46, 'stroke-linecap="butt"')
    body += p(rrect(88, 230, 336, 252, 50), INK)
    # keyhole
    body += p(circle(256, 292, 17), YEL)
    body += p(poly([(248, 300), (264, 300), (270, 334), (242, 334)]), YEL)
    size = RC.fit("PRIVATE", 244, 58, tracking=40)
    cap = size * RC.cap / RC.upm
    d, _ = RC.path("PRIVATE", size, 256, 374 + cap, anchor="middle", tracking=40)
    body += p(d, YEL)
    return svg(512, 512, body)


def smiley():
    cx = cy = 256
    body = p(circle(cx, cy, 252), WHITE) + p(circle(cx, cy, 236), YEL)
    face = p(ellipse(200, 210, 21, 37), INK) + p(ellipse(312, 210, 21, 37), INK)
    face += stroke("M150 292Q256 418 362 292", INK, 28)
    body += f'<g transform="rotate(-9 256 256)">{face}</g>'
    return svg(512, 512, body)


def yuanchuang():
    body = p(rrect(6, 6, 500, 500, 50), WHITE)
    body += p(rrect(22, 22, 468, 468, 36), VERM)
    body += f'<path d="{rrect(50, 50, 412, 412, 14)}" fill="none" stroke="{WHITE}" stroke-width="7"/>'
    body += p(grid2x2(SONG, "原创作品", 176, 256, 256, 6), WHITE)
    return svg(512, 512, body)


def jinzhi():
    cx = cy = 256
    body = p(circle(cx, cy, 252), WHITE)
    body += p(ring(cx, cy, 238, 198), RED)
    body += p(grid2x2(HAN, "禁止转载", 128, 256, 256, 6), INK)
    return svg(512, 512, body)


def daotu():
    sh = "M48 84Q156 74 256 26Q356 74 464 84V286C464 404 378 482 256 530C134 482 48 404 48 286Z"
    body = f'<path d="{sh}" fill="{WHITE}" stroke="{WHITE}" stroke-width="30" stroke-linejoin="round"/>'
    body += p(sh, INK)
    body += (
        f'<path d="{sh}" fill="none" stroke="{YEL}" stroke-width="5" stroke-linejoin="round" '
        f'transform="translate(256 278) scale(0.88) translate(-256 -278)"/>'
    )
    body += p(grid2x2(HAN, "盗图必究", 122, 256, 262, 8), YEL)
    return svg(512, 556, body)


# ---------------------------------------------------------------- demo "mine" stickers (not shipped)
def mine_signature():
    s1 = SERIF_I.size_for_cap(120)
    d1, w1 = SERIF_I.path("rosu.", s1, 20, 190)
    s2 = MONO.size_for_cap(24)
    d2, w2 = MONO.path("PHOTOGRAPHY", s2, 26, 250, tracking=260)
    W = max(w1, w2) + 46
    return svg(W, 280, p(d1, WHITE) + p(d2, WHITE))


def mine_dark_logo():
    cx, cy = 150, 150
    body = p(ring(cx, cy, 132, 112), "#101010")
    blades = ""
    for i in range(6):
        a = math.radians(60 * i)
        b = math.radians(60 * i + 96)
        blades += stroke(
            f"M{n(cx+112*math.cos(a))} {n(cy+112*math.sin(a))}L{n(cx+44*math.cos(b))} {n(cy+44*math.sin(b))}",
            "#101010",
            12,
            'stroke-linecap="butt"',
        )
    body += blades
    s = RC.size_for_cap(92)
    d, w = RC.path("STUDIO 27", s, 318, 196, tracking=24)
    body += p(d, "#101010")
    return svg(318 + w + 20, 300, body)


def mine_handle():
    s = SANS.size_for_cap(84)
    w = SANS.width("@rosu.jpg", s, 4)
    H, W = 170, w + 120
    body = p(rrect(8, 8, W, H, H / 2), WHITE)
    d, _ = SANS.path("@rosu.jpg", s, 8 + 60, 8 + H / 2 + 42, tracking=4)
    defs = (
        f'<linearGradient id="h" gradientUnits="userSpaceOnUse" x1="68" y1="0" x2="{n(68+w)}" y2="0">'
        '<stop offset="0" stop-color="#FF7A00"/><stop offset="1" stop-color="#FF2D6F"/></linearGradient>'
    )
    body += p(d, "url(#h)")
    return svg(W + 16, H + 16, body, defs)


PACK = [
    ("verified", "认证", "Verified", verified),
    ("original", "原创标签", "Original", original),
    ("no_ai", "禁止 AI 训练", "No AI training", no_ai),
    ("no_repost", "禁止转发", "No repost", no_repost),
    ("do_not_copy", "禁止复制", "Do not copy", do_not_copy),
    ("copyright", "版权所有", "All rights reserved", copyright_seal),
    ("sample", "样张", "Sample", sample),
    ("private", "私密", "Private", private),
    ("smiley", "笑脸", "Smiley", smiley),
    ("seal_original", "原创作品印", "Original work seal", yuanchuang),
    ("no_reprint", "禁止转载", "No reprint", jinzhi),
    ("anti_theft", "盗图必究", "Anti-theft", daotu),
]
MINE = [
    ("mine_signature", mine_signature),
    ("mine_dark_logo", mine_dark_logo),
    ("mine_handle", mine_handle),
]

if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    pack = {"builtin": [], "mine": []}
    for key, zh, en, fn in PACK:
        s = fn()
        open(os.path.join(out, key + ".svg"), "w", encoding="utf-8").write(s)
        pack["builtin"].append({"key": key, "zh": zh, "en": en, "svg": s})
        print(f"{key:16s} {len(s):7d} bytes")
    for key, fn in MINE:
        s = fn()
        open(os.path.join(out, key + ".svg"), "w", encoding="utf-8").write(s)
        pack["mine"].append({"key": key, "svg": s})
        print(f"{key:16s} {len(s):7d} bytes")
    json.dump(pack, open(os.path.join(out, "pack.json"), "w", encoding="utf-8"), ensure_ascii=False)
