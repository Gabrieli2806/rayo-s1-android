#!/usr/bin/env python3
"""Generate the GabOS app icons (vector drawables) and TV banners (PNG).

Writes into the res/ folders of the GabOS apps:
  work/gabos-media, work/gabos-diagnostics, work/rayo-validation (Hardware
  Check) and work/rayo-tv (GabOS Settings activity).
Banners are 320x180 dp, stored as 640x360 px in drawable-xhdpi, with an English
default and a Spanish (drawable-es-xhdpi) variant because they carry the name.
Requires Pillow and the Windows Segoe UI fonts (or pass --font-dir).
Run: python work/rayo-tv/art/generate-art.py
"""
from pathlib import Path
import argparse
import math

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
W, H, S = 640, 360, 3  # banner size and supersampling factor

APPS = {
    'media': dict(res='work/gabos-media/res', prefix='', top=(0x9a, 0x45, 0x78), bottom=(0x3a, 0x15, 0x30),
                  names={'': 'Media', 'es': 'Multimedia'}),
    'diagnostics': dict(res='work/gabos-diagnostics/res', prefix='', top=(0x45, 0x55, 0xa3), bottom=(0x1b, 0x22, 0x50),
                        names={'': 'Diagnostics', 'es': 'Diagnóstico'}),
    'check': dict(res='work/rayo-validation/res', prefix='', top=(0x3d, 0x84, 0x4c), bottom=(0x15, 0x37, 0x1d),
                  names={'': 'Hardware Check', 'es': 'Prueba de hardware'}),
    'settings': dict(res='work/rayo-tv/res', prefix='settings_', top=(0x2a, 0x5c, 0xc0), bottom=(0x0f, 0x2a, 0x6a),
                     names={'': 'Settings', 'es': 'Ajustes'}),
}


def hexcolor(rgb):
    return '#FF%02X%02X%02X' % rgb


# ----------------------------------------------------------------- glyphs
# Each glyph is drawn in a 48x48 box; vector() gives the same shape as XML paths.

def gear_teeth():
    lines = []
    for i in range(8):
        a = math.pi * i / 4
        x1, y1 = 24 + math.cos(a) * 10, 24 + math.sin(a) * 10
        x2, y2 = 24 + math.cos(a) * 13.2, 24 + math.sin(a) * 13.2
        lines.append((x1, y1, x2, y2))
    return lines


def vector_glyph(kind):
    stroke = 'android:strokeColor="#FFFFFFFF" android:strokeLineCap="round" android:strokeLineJoin="round"'
    if kind == 'media':
        return ['<path android:fillColor="#FFFFFFFF" android:pathData="M19,14.5 L35,24 L19,33.5 Z"/>']
    if kind == 'diagnostics':
        return [f'<path android:fillColor="#00000000" {stroke} android:strokeWidth="3" '
                'android:pathData="M9,25 L16,25 L19.5,16 L25,33 L29,20 L31.5,25 L39,25"/>']
    if kind == 'check':
        return [f'<path android:fillColor="#00000000" {stroke} android:strokeWidth="2.6" '
                'android:pathData="M16,14 L32,14 Q34,14 34,16 L34,32 Q34,34 32,34 L16,34 Q14,34 14,32 L14,16 Q14,14 16,14 Z"/>',
                f'<path android:fillColor="#00000000" {stroke} android:strokeWidth="2.2" '
                'android:pathData="M19,10 L19,14 M24,10 L24,14 M29,10 L29,14 M19,34 L19,38 M24,34 L24,38 M29,34 L29,38 '
                'M10,19 L14,19 M10,24 L14,24 M10,29 L14,29 M34,19 L38,19 M34,24 L38,24 M34,29 L38,29"/>',
                f'<path android:fillColor="#00000000" {stroke} android:strokeWidth="3" android:pathData="M19,24.5 L22.5,28 L29.5,20.5"/>']
    if kind == 'settings':
        teeth = ' '.join('M%.2f,%.2f L%.2f,%.2f' % t for t in gear_teeth())
        return [f'<path android:fillColor="#00000000" {stroke} android:strokeWidth="4.4" android:pathData="{teeth}"/>',
                f'<path android:fillColor="#00000000" {stroke} android:strokeWidth="3" '
                'android:pathData="M24,14 A10,10 0 1,1 24,34 A10,10 0 1,1 24,14 Z"/>',
                f'<path android:fillColor="#00000000" {stroke} android:strokeWidth="2.6" '
                'android:pathData="M24,20.5 A3.5,3.5 0 1,1 24,27.5 A3.5,3.5 0 1,1 24,20.5 Z"/>']
    raise ValueError(kind)


def draw_glyph(d, kind, ox, oy, scale, color=(255, 255, 255, 255)):
    def p(x, y):
        return (ox + x * scale, oy + y * scale)

    def line(points, width):
        w = int(width * scale)
        d.line([p(*q) for q in points], fill=color, width=w, joint='curve')
        for q in (points[0], points[-1]):
            cx, cy = p(*q)
            d.ellipse([cx - w / 2, cy - w / 2, cx + w / 2, cy + w / 2], fill=color)

    def ring(cx, cy, r, width):
        cx, cy = p(cx, cy)
        r *= scale
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=int(width * scale))

    if kind == 'media':
        d.polygon([p(19, 14.5), p(35, 24), p(19, 33.5)], fill=color)
    elif kind == 'diagnostics':
        line([(9, 25), (16, 25), (19.5, 16), (25, 33), (29, 20), (31.5, 25), (39, 25)], 3)
    elif kind == 'check':
        x0, y0 = p(14, 14)
        x1, y1 = p(34, 34)
        d.rounded_rectangle([x0, y0, x1, y1], radius=2 * scale, outline=color, width=int(2.6 * scale))
        for v in (19, 24, 29):
            line([(v, 10), (v, 13)], 2.2)
            line([(v, 35), (v, 38)], 2.2)
            line([(10, v), (13, v)], 2.2)
            line([(35, v), (38, v)], 2.2)
        line([(19, 24.5), (22.5, 28), (29.5, 20.5)], 3)
    elif kind == 'settings':
        for x1, y1, x2, y2 in gear_teeth():
            line([(x1, y1), (x2, y2)], 4.4)
        ring(24, 24, 10, 3)
        ring(24, 24, 3.5, 2.6)


# ----------------------------------------------------------------- outputs

def write_icon(app, conf):
    res = ROOT / conf['res'] / 'drawable'
    res.mkdir(parents=True, exist_ok=True)
    body = '\n  '.join(vector_glyph(app))
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by work/rayo-tv/art/generate-art.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
  <path android:fillColor="{hexcolor(conf['top'])}" android:pathData="M24,1 A23,23 0 1,1 24,47 A23,23 0 1,1 24,1 Z"/>
  {body}
</vector>
'''
    (res / f"{conf['prefix']}icon.xml").write_text(xml, encoding='utf-8')


def font(path, size):
    return ImageFont.truetype(str(path), size)


def fit(draw, text, path, start, room, lines_allowed=2):
    """Largest size that fits the room on one line, or on two lines when needed."""
    size = start
    while size > 20:
        f = font(path, size)
        if draw.textlength(text, font=f) <= room:
            return f, [text]
        words = text.split(' ')
        if lines_allowed > 1 and len(words) > 1:
            for cut in range(len(words) - 1, 0, -1):
                a, b = ' '.join(words[:cut]), ' '.join(words[cut:])
                if max(draw.textlength(a, font=f), draw.textlength(b, font=f)) <= room:
                    return f, [a, b]
        size -= 2
    return font(path, size), [text]


def write_banner(app, conf, lang, fonts):
    w, h = W * S, H * S
    top, bottom = conf['top'], conf['bottom']
    # Diagonal gradient: the mean of a vertical and a horizontal ramp.
    vertical = Image.linear_gradient('L').resize((w, h))
    horizontal = Image.linear_gradient('L').rotate(90).transpose(Image.FLIP_LEFT_RIGHT).resize((w, h))
    gradient = Image.blend(vertical, horizontal, .5)
    image = Image.composite(Image.new('RGBA', (w, h), bottom + (255,)), Image.new('RGBA', (w, h), top + (255,)), gradient)
    # Soft decorative circles and the glyph disc, blended through an overlay.
    overlay = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    o = ImageDraw.Draw(overlay)
    o.ellipse([w * .66, -h * .45, w * 1.3, h * .7], fill=(255, 255, 255, 16))
    o.ellipse([w * .8, h * .5, w * 1.2, h * 1.3], fill=(0, 0, 0, 34))
    disc = 150 * S
    cx, cy = 52 * S + disc / 2, h / 2
    o.ellipse([cx - disc / 2, cy - disc / 2, cx + disc / 2, cy + disc / 2], fill=(255, 255, 255, 38))
    image = Image.alpha_composite(image, overlay)
    d = ImageDraw.Draw(image)
    scale = disc / 48 * .92
    draw_glyph(d, app, cx - 24 * scale, cy - 24 * scale, scale)
    # Wordmark and name.
    x = cx + disc / 2 + 34 * S
    room = w - x - 30 * S
    small = font(fonts['semibold'], 26 * S)
    name_font, lines = fit(d, conf['names'][lang], fonts['bold'], 64 * S, room)
    line_h = name_font.size * 1.12
    block = 26 * S + 14 * S + line_h * len(lines)
    y = (h - block) / 2
    text_layer = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    t = ImageDraw.Draw(text_layer)
    t.text((x, y), ' '.join('GabOS'), font=small, fill=(255, 255, 255, 200))
    y += 26 * S + 14 * S
    for text in lines:
        t.text((x, y), text, font=name_font, fill=(255, 255, 255, 255))
        y += line_h
    image = Image.alpha_composite(image, text_layer)
    out = image.resize((W, H), Image.LANCZOS).convert('RGB')
    folder = ROOT / conf['res'] / ('drawable-' + (lang + '-' if lang else '') + 'xhdpi')
    folder.mkdir(parents=True, exist_ok=True)
    out.save(folder / f"{conf['prefix']}banner.png", optimize=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font-dir', type=Path, default=Path('C:/Windows/Fonts'))
    args = parser.parse_args()
    fonts = {'bold': args.font_dir / 'segoeuib.ttf', 'semibold': args.font_dir / 'seguisb.ttf'}
    for app, conf in APPS.items():
        write_icon(app, conf)
        for lang in conf['names']:
            write_banner(app, conf, lang, fonts)
    print('Icons and banners written')


if __name__ == '__main__':
    main()
