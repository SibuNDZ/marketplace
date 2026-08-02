"""
Generates the eRestyu brand mark as favicon assets. Run once, output committed
to public/. Not part of the build pipeline (no image lib in package.json) —
regenerate by hand if the mark ever changes, same geometry as LogoMark.tsx.

Mark: a bold paper lowercase "e" on the flame-gradient tile the eRestyu
wordmark itself uses (linear, 135deg, #FF9A3D -> #FF4626 at 60% -> #E31C3D).
Font is Georgia Bold, matching the header mark's font-family.
"""
import os
from PIL import Image, ImageDraw, ImageFont

STOPS = [(0.0, (0xFF, 0x9A, 0x3D)), (0.6, (0xFF, 0x46, 0x26)), (1.0, (0xE3, 0x1C, 0x3D))]
PAPER = (245, 247, 243, 255)
FONT_PATH = r"C:\Windows\Fonts\georgiab.ttf"


def lerp(a, b, t):
    return a + (b - a) * t


def stop_color(t: float):
    t = max(0.0, min(1.0, t))
    for (f0, c0), (f1, c1) in zip(STOPS, STOPS[1:]):
        if f0 <= t <= f1:
            local = 0 if f1 == f0 else (t - f0) / (f1 - f0)
            return tuple(int(round(lerp(c0[i], c1[i], local))) for i in range(3))
    return STOPS[-1][1]


def flame_gradient(size: int) -> Image.Image:
    # 135deg CSS gradient == diagonal from top-left (0%) to bottom-right
    # (100%). Build it as a top-to-bottom ramp, then rotate 45 degrees and
    # crop back to size — cheap and exact enough for an icon.
    big = size * 3
    grad = Image.linear_gradient("L").resize((big, big), Image.BICUBIC)
    grad = grad.rotate(-45, resample=Image.BICUBIC, expand=False)
    left = (grad.width - size) // 2
    top = (grad.height - size) // 2
    grad = grad.crop((left, top, left + size, top + size))

    lut_r = [0] * 256
    lut_g = [0] * 256
    lut_b = [0] * 256
    for v in range(256):
        r, g, b = stop_color(v / 255)
        lut_r[v], lut_g[v], lut_b[v] = r, g, b
    return Image.merge("RGB", (grad.point(lut_r), grad.point(lut_g), grad.point(lut_b)))


def draw_mark(size: int) -> Image.Image:
    S = size * 4  # supersample for clean anti-aliasing at small sizes
    corner_r = int(S * 0.22)

    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=corner_r, fill=255)

    tile = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    tile.paste(flame_gradient(S), (0, 0), mask)

    d = ImageDraw.Draw(tile)
    font_size = int(S * 0.66)
    font = ImageFont.truetype(FONT_PATH, font_size)
    bbox = d.textbbox((0, 0), "e", font=font)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    # Nudge up slightly: a lowercase e's visual weight sits below the cap
    # line, and centering on the raw bbox alone reads a touch low.
    pos = (S / 2 - w / 2 - bbox[0], S / 2 - h / 2 - bbox[1] - S * 0.02)
    d.text(pos, "e", font=font, fill=PAPER)

    return tile.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "..", "public")
    os.makedirs(out, exist_ok=True)

    draw_mark(512).save(os.path.join(out, "icon-512.png"))
    draw_mark(180).save(os.path.join(out, "apple-touch-icon.png"))

    icons = [draw_mark(s) for s in (16, 32, 48)]
    icons[0].save(
        os.path.join(out, "favicon.ico"),
        format="ICO",
        sizes=[(16, 16), (32, 32), (48, 48)],
        append_images=icons[1:],
    )

    print("wrote icon-512.png, apple-touch-icon.png, favicon.ico")
