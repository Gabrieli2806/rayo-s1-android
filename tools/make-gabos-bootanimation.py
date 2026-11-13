#!/usr/bin/env python3
"""Render the GabOS wordmark boot animation and brand images.

The wordmark is "GabOS" in Roboto Thin (the variable Roboto font from the
Android source, Apache 2.0, weight axis set to 100) with letter spacing.
Animation: the letters fade in one after another while a soft light sweep
passes, then the finished wordmark breathes gently until boot completes.

Outputs (default outputs/):
  gabos-bootanimation.zip  -> /system/media/bootanimation.zip (stored, no compression)
  gabos-wordmark.png       -> 1280x720 still for previews
  gabos-icon.png           -> 192x192 launcher icon
"""
import argparse
import io
import math
from pathlib import Path
import zipfile
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
W, H, FPS = 1280, 720, 30
TEXT = 'GabOS'


def font(path, size, weight=100):
    f = ImageFont.truetype(str(path), size)
    f.set_variation_by_axes([weight, 100, 0])
    return f


def layout(f, spacing):
    """Per-letter x offsets for a tracked, centered wordmark."""
    widths = [f.getlength(ch) for ch in TEXT]
    total = sum(widths) + spacing * (len(TEXT) - 1)
    x, xs = (W - total) / 2, []
    for w in widths:
        xs.append(x)
        x += w + spacing
    bbox = f.getbbox(TEXT)
    y = (H - (bbox[3] - bbox[1])) / 2 - bbox[1]
    return xs, y, total


def frame(f, xs, y, total, alphas, sweep=None, glow=0.0):
    image = Image.new('RGB', (W, H), (0, 0, 0))
    layer = Image.new('L', (W, H), 0)
    draw = ImageDraw.Draw(layer)
    for ch, x, a in zip(TEXT, xs, alphas):
        if a > 0:
            draw.text((x, y), ch, font=f, fill=int(255 * min(1, a)))
    if glow > 0:
        halo = layer.filter(ImageFilter.GaussianBlur(14)).point(lambda v: int(v * glow))
        image.paste((120, 160, 255), mask=halo)
    base = Image.new('RGB', (W, H), (236, 240, 245))
    image.paste(base, mask=layer)
    if sweep is not None:
        # A narrow soft highlight crossing the letters.
        band = Image.new('L', (W, H), 0)
        bd = ImageDraw.Draw(band)
        cx = (W - total) / 2 - 120 + sweep * (total + 240)
        for i in range(60):
            bd.line([(cx - 30 + i, 0), (cx - 30 + i - 80, H)], fill=int(170 * math.sin(math.pi * i / 59)))
        band = band.filter(ImageFilter.GaussianBlur(6))
        highlight = Image.new('L', (W, H), 0)
        highlight.paste(band, mask=layer)
        image.paste((255, 255, 255), mask=highlight)
    return image


def png(image):
    out = io.BytesIO()
    image.convert('RGB').save(out, 'PNG', optimize=True)
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', type=Path, default=ROOT/'local/fonts/Roboto-Regular.ttf')
    parser.add_argument('--out', type=Path, default=ROOT/'outputs')
    args = parser.parse_args()
    f = font(args.font, 150)
    xs, y, total = layout(f, 26)
    intro, loop = [], []
    n = len(TEXT)
    for i in range(48):  # 1.6 s: letters appear in turn, sweep follows
        t = i / 47
        alphas = [max(0.0, min(1.0, (t * (n + 2) - k) / 2.0)) for k in range(n)]
        sweep = (t - 0.35) / 0.65 if t > 0.35 else None
        intro.append(frame(f, xs, y, total, alphas, sweep))
    for i in range(60):  # 2 s breathing loop
        g = 0.18 + 0.18 * (1 - math.cos(2 * math.pi * i / 60)) / 2
        loop.append(frame(f, xs, y, total, [1] * n, None, g))
    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out/'gabos-bootanimation.zip'
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('desc.txt', '%d %d %d\np 1 0 part0\np 0 0 part1\n' % (W, H, FPS))
        for name, frames in (('part0', intro), ('part1', loop)):
            for k, image in enumerate(frames):
                z.writestr('%s/%03d.png' % (name, k), png(image))
    loop[0].save(args.out/'gabos-wordmark.png')
    # Launcher icon: thin wordmark on a dark rounded square.
    icon = Image.new('RGBA', (192, 192), (0, 0, 0, 0))
    d = ImageDraw.Draw(icon)
    d.rounded_rectangle((0, 0, 191, 191), 40, fill=(16, 20, 28, 255))
    small = font(args.font, 50, 200)
    bbox = small.getbbox(TEXT)
    d.text(((192 - (bbox[2] - bbox[0])) / 2 - bbox[0], (192 - (bbox[3] - bbox[1])) / 2 - bbox[1]),
           TEXT, font=small, fill=(236, 240, 245, 255))
    icon.save(args.out/'gabos-icon.png')
    print(target, target.stat().st_size)


if __name__ == '__main__':
    main()
