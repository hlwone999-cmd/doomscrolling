#!/usr/bin/env python3
"""Renders Tidemark brand assets from one geometry (the same as the Android
vector drawables in res/drawable/ic_launcher_*.xml). Needs Pillow.

    python replica/brand/render.py

Writes into replica/brand/:
  icon.svg, icon-monochrome.svg   master icon (108 viewport, full-bleed tile)
  logo.svg                        symbol + wordmark (wordmark as text in Inter)
  play-icon-512.png               Google Play store icon (512x512, full bleed)
  feature-graphic-1024x500.png    Google Play feature graphic
  icon-preview.png                the icon under circle / squircle / rounded masks on light and dark
  icon-16.png, icon-48.png        small-size legibility check
"""
import json
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
TOKENS = json.load(open(os.path.join(HERE, "..", "design", "tokens.json"), encoding="utf-8"))
DEEP = TOKENS["color"]["brand"]["deep"]
FOAM = TOKENS["color"]["brand"]["foam"]
MARK = TOKENS["color"]["brand"]["mark"]
FONT = os.path.join(HERE, "fonts", "Inter[opsz,wght].ttf")

# Geometry in the 108-unit adaptive-icon viewport (keep in step with the XML).
GAUGE = [((37, 35), (37, 74)), ((37, 39), (41, 39)), ((37, 47), (44, 47)), ((37, 55), (41, 55)), ((37, 63), (44, 63))]
GAUGE_W = 3
MARKLINE = ((50, 47), (73, 47))
MARK_W = 4
WAVE = [("M", (48, 74)), ("L", (48, 63.5)), ("C", (51.5, 58), (55.5, 56.5), (59.5, 59.5)),
        ("C", (62.8, 62), (66, 63.2), (69.2, 61.6)), ("C", (70.8, 60.8), (72, 59.6), (73, 58.4)), ("L", (73, 74))]
WAVE_D = "M48,74V63.5C51.5,58 55.5,56.5 59.5,59.5C62.8,62 66,63.2 69.2,61.6C70.8,60.8 72,59.6 73,58.4V74Z"
GAUGE_D = "M37,35V74M37,39h4M37,47h7M37,55h4M37,63h7"


def svg_icon(mono=False):
    fg = "#FFFFFF" if mono else FOAM
    mk = "#FFFFFF" if mono else MARK
    bg = "" if mono else '<rect width="108" height="108" fill="%s"/>' % DEEP
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="1024" height="1024">'
            '<title>Tidemark</title>%s'
            '<path d="%s" fill="none" stroke="%s" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
            '<path d="M50,47H73" stroke="%s" stroke-width="4" stroke-linecap="round"/>'
            '<path d="%s" fill="%s"/></svg>\n') % (bg, GAUGE_D, fg, mk, WAVE_D, fg)


def svg_logo():
    # Symbol in a rounded tile + wordmark. Text kept as text: outline it in a
    # design tool before using it anywhere Inter isn't installed.
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 108" width="840" height="216">'
            '<title>Tidemark</title>'
            '<rect width="108" height="108" rx="24" fill="%s"/>'
            '<path d="%s" fill="none" stroke="%s" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
            '<path d="M50,47H73" stroke="%s" stroke-width="4" stroke-linecap="round"/>'
            '<path d="%s" fill="%s"/>'
            '<text x="128" y="72" font-family="Inter, sans-serif" font-weight="700" font-size="52" fill="%s" letter-spacing="-1">Tidemark</text>'
            '</svg>\n') % (DEEP, GAUGE_D, FOAM, MARK, WAVE_D, FOAM, DEEP)


def bezier(p0, p1, p2, p3, n=24):
    pts = []
    for i in range(1, n + 1):
        t = i / n
        a = (1 - t) ** 3; b = 3 * (1 - t) ** 2 * t; c = 3 * (1 - t) * t ** 2; d = t ** 3
        pts.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return pts


def wave_polygon():
    pts, cur = [], None
    for cmd in WAVE:
        if cmd[0] in ("M", "L"):
            cur = cmd[1]; pts.append(cur)
        else:
            seg = bezier(cur, cmd[1], cmd[2], cmd[3]); pts += seg; cur = seg[-1]
    return pts


def draw_icon(size, mono=False, bg=True):
    """Full-bleed icon at `size` px, drawn 4x and downsampled for smooth edges."""
    ss = 4
    S = size * ss
    k = S / 108.0
    img = Image.new("RGBA", (S, S), DEEP if bg else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    fg = "#FFFFFF" if mono else FOAM
    mk = "#FFFFFF" if mono else MARK

    def line(a, b, w, col):
        a = (a[0] * k, a[1] * k); b = (b[0] * k, b[1] * k); r = w * k / 2
        d.line([a, b], fill=col, width=max(1, round(w * k)))
        for p in (a, b):
            d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=col)

    for a, b in GAUGE:
        line(a, b, GAUGE_W, fg)
    line(MARKLINE[0], MARKLINE[1], MARK_W, mk)
    d.polygon([(x * k, y * k) for x, y in wave_polygon()], fill=fg)
    return img.resize((size, size), Image.LANCZOS)


def mask(img, shape):
    s = img.size[0]
    m = Image.new("L", (s * 4, s * 4), 0)
    md = ImageDraw.Draw(m)
    if shape == "circle":
        md.ellipse([0, 0, s * 4 - 1, s * 4 - 1], fill=255)
    elif shape == "squircle":
        md.rounded_rectangle([0, 0, s * 4 - 1, s * 4 - 1], radius=int(s * 4 * 0.32), fill=255)
    else:
        md.rounded_rectangle([0, 0, s * 4 - 1, s * 4 - 1], radius=int(s * 4 * 0.18), fill=255)
    m = m.resize((s, s), Image.LANCZOS)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


def launcher_view(full, shape):
    """What a launcher shows: the 108 viewport scaled so the 72-unit visible area fills the mask."""
    s = full.size[0]
    crop = int(s * 18 / 108)
    visible = full.crop((crop, crop, s - crop, s - crop)).resize((s, s), Image.LANCZOS)
    return mask(visible, shape)


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([14 if size < 40 else 32, weight])  # opsz, wght
    except Exception:
        pass
    return f


def main():
    open(os.path.join(HERE, "icon.svg"), "w", encoding="utf-8").write(svg_icon())
    open(os.path.join(HERE, "icon-monochrome.svg"), "w", encoding="utf-8").write(svg_icon(mono=True))
    open(os.path.join(HERE, "logo.svg"), "w", encoding="utf-8").write(svg_logo())

    # Play Store icon: full bleed, Google applies its own mask. Opaque.
    play = draw_icon(512).convert("RGB")
    play.save(os.path.join(HERE, "play-icon-512.png"))

    # Feature graphic 1024x500: tile + wordmark + one line, centred safe area.
    fg = Image.new("RGB", (1024, 500), "#07363D")  # darker than the tile so the tile reads
    tile = launcher_view(draw_icon(220), "squircle")
    fg.paste(tile, (150, 140), tile)
    d = ImageDraw.Draw(fg)
    d.text((400, 170), "Tidemark", font=font(96, 700), fill=FOAM)
    d.text((404, 290), "See how far you scrolled. Then let it ebb.", font=font(30, 500), fill=FOAM)
    d.line([(404, 345), (560, 345)], fill=MARK, width=6)
    fg.save(os.path.join(HERE, "feature-graphic-1024x500.png"))

    # Preview sheet: 3 launcher masks on a light and a dark wallpaper, plus the themed icon.
    full = draw_icon(192)
    mono = draw_icon(192, mono=True, bg=False)
    sheet = Image.new("RGB", (4 * 240 + 40, 2 * 300 + 40), "#FFFFFF")
    sd = ImageDraw.Draw(sheet)
    for row, (wall, label_col) in enumerate([("#F1F3F6", "#12151A"), ("#101318", "#ECEEF2")]):
        y0 = 20 + row * 300
        sd.rectangle([20, y0, sheet.size[0] - 20, y0 + 280], fill=wall)
        for i, shape in enumerate(["circle", "squircle", "rounded"]):
            v = launcher_view(full, shape)
            sheet.paste(v, (44 + i * 240, y0 + 24), v)
            sd.text((44 + i * 240, y0 + 230), "%s · %s" % (shape, "light" if row == 0 else "dark"), font=font(18, 500), fill=label_col)
        # themed (Android 13+): monochrome tinted on a tonal tile
        tint_bg, tint_fg = ("#D6E8EA", "#0B4F59") if row == 0 else ("#1E3A3E", "#BFE7EA")
        tile = Image.new("RGBA", (192, 192), tint_bg)
        tinted = Image.new("RGBA", (192, 192), tint_fg)
        tile.paste(tinted, (0, 0), mono.split()[3])
        v = launcher_view(tile, "circle")
        sheet.paste(v, (44 + 3 * 240, y0 + 24), v)
        sd.text((44 + 3 * 240, y0 + 230), "themed · %s" % ("light" if row == 0 else "dark"), font=font(18, 500), fill=label_col)
    sheet.save(os.path.join(HERE, "icon-preview.png"))

    for px in (16, 48):
        launcher_view(draw_icon(px * 4), "circle").resize((px, px), Image.LANCZOS).save(os.path.join(HERE, "icon-%d.png" % px))
    print("rendered:", ", ".join(sorted(f for f in os.listdir(HERE) if f.endswith((".png", ".svg")))))


if __name__ == "__main__":
    main()
