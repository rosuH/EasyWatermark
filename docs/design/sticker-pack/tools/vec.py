"""Tiny vector helpers: OFL font text -> SVG outlines (with GPOS pair kerning), shapes."""
import math, os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

FONTS = os.path.expanduser("~/Library/Fonts")


def n(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def _build_kern(font):
    if "GPOS" not in font:
        return lambda a, b: 0
    gpos = font["GPOS"].table
    idx = []
    for fr in gpos.FeatureList.FeatureRecord:
        if fr.FeatureTag == "kern":
            idx += fr.Feature.LookupListIndex
    lookups = []
    for li in sorted(set(idx)):
        lk = gpos.LookupList.Lookup[li]
        subs = []
        for st in lk.SubTable:
            if lk.LookupType == 9:
                st = st.ExtSubTable
            if type(st).__name__ == "PairPos":
                subs.append((st, {g: i for i, g in enumerate(st.Coverage.glyphs)}))
        if subs:
            lookups.append(subs)

    def kern(a, b):
        total = 0
        for subs in lookups:
            for st, cov in subs:
                if a not in cov:
                    continue
                if st.Format == 1:
                    hit = None
                    for pvr in st.PairSet[cov[a]].PairValueRecord:
                        if pvr.SecondGlyph == b:
                            hit = pvr
                            break
                    if hit is None:
                        continue
                    v = hit.Value1
                    total += getattr(v, "XAdvance", 0) or 0 if v is not None else 0
                    break
                else:
                    c1 = st.ClassDef1.classDefs.get(a, 0)
                    c2 = st.ClassDef2.classDefs.get(b, 0)
                    v = st.Class1Record[c1].Class2Record[c2].Value1
                    total += (getattr(v, "XAdvance", 0) or 0) if v is not None else 0
                    break
        return total

    return kern


class Font:
    def __init__(self, filename):
        self.f = TTFont(os.path.join(FONTS, filename))
        self.gs = self.f.getGlyphSet()
        self.cmap = self.f.getBestCmap()
        self.upm = self.f["head"].unitsPerEm
        self.cap = getattr(self.f["OS/2"], "sCapHeight", 0) or int(self.upm * 0.7)
        self.kern = _build_kern(self.f)

    def size_for_cap(self, cap_px):
        return cap_px * self.upm / self.cap

    def run(self, text, tracking=0):
        names = [self.cmap[ord(c)] for c in text]
        xs, x = [], 0.0
        tr = tracking * self.upm / 1000.0
        for i, g in enumerate(names):
            if i > 0:
                x += self.kern(names[i - 1], g)
            xs.append(x)
            x += self.gs[g].width + tr
        return names, xs, x - tr

    def width(self, text, size, tracking=0):
        return self.run(text, tracking)[2] * size / self.upm

    def path(self, text, size, x, y, anchor="start", tracking=0):
        """Outline `text` with baseline at y. Returns (d, width)."""
        s = size / self.upm
        names, xs, w = self.run(text, tracking)
        W = w * s
        x0 = x - (W / 2 if anchor == "middle" else W if anchor == "end" else 0)
        parts = []
        for g, gx in zip(names, xs):
            pen = SVGPathPen(self.gs, ntos=n)
            self.gs[g].draw(TransformPen(pen, (s, 0, 0, -s, x0 + gx * s, y)))
            c = pen.getCommands()
            if c:
                parts.append(c)
        return " ".join(parts), W

    def fit(self, text, max_width, max_cap, tracking=0):
        """Largest font size whose cap height <= max_cap and width <= max_width."""
        size = self.size_for_cap(max_cap)
        w = self.width(text, size, tracking)
        if w > max_width:
            size *= max_width / w
        return size

    def arc(self, text, size, cx, cy, r, start_deg=0.0, tracking=0, span_deg=None):
        """Text clockwise along a circle, glyph tops pointing outward, baseline on radius r.
        start_deg: angle of text start, 0 = top, clockwise. If span_deg is given, tracking is
        solved so the run exactly fills that span."""
        s = size / self.upm
        if span_deg is not None:
            names, xs, w0 = self.run(text, 0)
            target = math.radians(span_deg) * r / s
            gaps = len(names)  # closed ring: every glyph gets one gap
            tracking = (target - w0) / gaps * 1000.0 / self.upm
        names, xs, w = self.run(text, tracking)
        parts = []
        for g, gx in zip(names, xs):
            adv = self.gs[g].width
            th = math.radians(start_deg) + (gx + adv / 2) * s / r
            tx, ty = cx + r * math.sin(th), cy - r * math.cos(th)
            c, sn = math.cos(th), math.sin(th)
            m = (s * c, s * sn, s * sn, -s * c, tx - s * (adv / 2) * c, ty - s * (adv / 2) * sn)
            pen = SVGPathPen(self.gs, ntos=n)
            self.gs[g].draw(TransformPen(pen, m))
            cmd = pen.getCommands()
            if cmd:
                parts.append(cmd)
        return " ".join(parts)


# ---------- shapes ----------
def rrect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return (
        f"M{n(x+r)} {n(y)}H{n(x+w-r)}A{n(r)} {n(r)} 0 0 1 {n(x+w)} {n(y+r)}V{n(y+h-r)}"
        f"A{n(r)} {n(r)} 0 0 1 {n(x+w-r)} {n(y+h)}H{n(x+r)}A{n(r)} {n(r)} 0 0 1 {n(x)} {n(y+h-r)}"
        f"V{n(y+r)}A{n(r)} {n(r)} 0 0 1 {n(x+r)} {n(y)}Z"
    )


def circle(cx, cy, r):
    return f"M{n(cx-r)} {n(cy)}A{n(r)} {n(r)} 0 1 0 {n(cx+r)} {n(cy)}A{n(r)} {n(r)} 0 1 0 {n(cx-r)} {n(cy)}Z"


def ring(cx, cy, r_out, r_in):
    """Filled annulus (even-odd not needed: inner circle wound the other way)."""
    return (
        f"M{n(cx-r_out)} {n(cy)}A{n(r_out)} {n(r_out)} 0 1 0 {n(cx+r_out)} {n(cy)}"
        f"A{n(r_out)} {n(r_out)} 0 1 0 {n(cx-r_out)} {n(cy)}Z"
        f"M{n(cx-r_in)} {n(cy)}A{n(r_in)} {n(r_in)} 0 1 1 {n(cx+r_in)} {n(cy)}"
        f"A{n(r_in)} {n(r_in)} 0 1 1 {n(cx-r_in)} {n(cy)}Z"
    )


def poly(pts):
    return "M" + "L".join(f"{n(x)} {n(y)}" for x, y in pts) + "Z"


def ngon(cx, cy, r, sides, rot_deg=0.0):
    return poly(
        [
            (cx + r * math.cos(math.radians(rot_deg + 360.0 * i / sides)), cy + r * math.sin(math.radians(rot_deg + 360.0 * i / sides)))
            for i in range(sides)
        ]
    )


def sparkle(cx, cy, r, pinch=0.26):
    """Four-point star with concave sides."""
    k = r * pinch
    return (
        f"M{n(cx)} {n(cy-r)}Q{n(cx+k)} {n(cy-k)} {n(cx+r)} {n(cy)}Q{n(cx+k)} {n(cy+k)} {n(cx)} {n(cy+r)}"
        f"Q{n(cx-k)} {n(cy+k)} {n(cx-r)} {n(cy)}Q{n(cx-k)} {n(cy-k)} {n(cx)} {n(cy-r)}Z"
    )


def p(d, fill, extra=""):
    return f'<path d="{d}" fill="{fill}"{(" " + extra) if extra else ""}/>'


def stroke(d, color, width, extra='stroke-linecap="round" stroke-linejoin="round"'):
    return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{n(width)}" {extra}/>'


def svg(w, h, body, defs=""):
    d = f"<defs>{defs}</defs>" if defs else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n(w)} {n(h)}" width="{n(w)}" height="{n(h)}">{d}{body}</svg>'
