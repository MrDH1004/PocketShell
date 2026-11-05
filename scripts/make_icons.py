"""PocketShell icon generator: deep navy squircle + gold ">_" prompt.

Writes assets/pocketshell.ico (app), assets/tray-running.ico / tray-stopped.ico (tray states) and
assets/pocketshell-1024.png (preview). The Android adaptive icon (res/drawable/ic_launcher_*.xml) uses the
same geometry, scaled from this 1024 canvas to 72dp at offset 18dp.

Usage: py -3.14 scripts/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets"
S = 2048  # supersampled canvas (2x the 1024 design grid)
K = S / 1024

BG_TOP = (30, 41, 72)
BG_BOTTOM = (9, 13, 26)
GOLD_TOP = (247, 222, 160)
GOLD_BOTTOM = (201, 150, 62)


def vgradient(size, top, bottom):
    w, h = size
    col = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / (h - 1)
        col.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return col.resize((w, h))


def squircle_mask(radius=230):
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, S - 1, S - 1), radius=int(radius * K), fill=255)
    return m


def glyph_mask():
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    w = int(96 * K)
    pts = [(300 * K, 330 * K), (520 * K, 512 * K), (300 * K, 694 * K)]
    d.line(pts, fill=255, width=w, joint="curve")
    r = w / 2
    for x, y in (pts[0], pts[2]):
        d.ellipse((x - r, y - r, x + r, y + r), fill=255)
    d.rounded_rectangle((560 * K, 640 * K, 760 * K, 736 * K), radius=int(26 * K), fill=255)
    return m


def render(running=True):
    bg = vgradient((S, S), BG_TOP, BG_BOTTOM).convert("RGBA")
    # Soft top-left glow and a hairline inner border for depth.
    glow = Image.new("L", (S, S), 0)
    ImageDraw.Draw(glow).ellipse((-0.35 * S, -0.55 * S, 0.9 * S, 0.55 * S), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.08))
    bg = Image.composite(Image.new("RGBA", (S, S), (120, 150, 230, 255)), bg, glow.point(lambda v: v * 0.35))
    border = Image.new("L", (S, S), 0)
    ImageDraw.Draw(border).rounded_rectangle((6 * K, 6 * K, S - 6 * K, S - 6 * K), radius=int(224 * K),
                                             outline=40, width=int(5 * K))
    bg = Image.composite(Image.new("RGBA", (S, S), (255, 255, 255, 255)), bg, border)

    gm = glyph_mask()
    if running:
        gold = vgradient((S, S), GOLD_TOP, GOLD_BOTTOM).convert("RGBA")
    else:
        gold = vgradient((S, S), (170, 176, 190), (110, 116, 130)).convert("RGBA")
    shadow = gm.filter(ImageFilter.GaussianBlur(14 * K)).point(lambda v: v * 0.55)
    bg = Image.composite(Image.new("RGBA", (S, S), (0, 0, 0, 255)), bg, ImageChops.offset(shadow, 0, int(10 * K)))
    bg = Image.composite(gold, bg, gm)

    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(bg, (0, 0), squircle_mask())
    return out


def main():
    OUT.mkdir(exist_ok=True)
    app = render(True)
    app.resize((1024, 1024), Image.LANCZOS).save(OUT / "pocketshell-1024.png")
    sizes = [(s, s) for s in (16, 20, 24, 32, 40, 48, 64, 128, 256)]
    app.resize((256, 256), Image.LANCZOS).save(OUT / "pocketshell.ico", sizes=sizes)
    tray_sizes = [(s, s) for s in (16, 20, 24, 32, 40, 48, 64)]
    app.resize((64, 64), Image.LANCZOS).save(OUT / "tray-running.ico", sizes=tray_sizes)
    render(False).resize((64, 64), Image.LANCZOS).save(OUT / "tray-stopped.ico", sizes=tray_sizes)
    for name in ("pocketshell.ico", "tray-running.ico", "tray-stopped.ico", "pocketshell-1024.png"):
        print(name, (OUT / name).stat().st_size)


if __name__ == "__main__":
    main()
