#!/usr/bin/env python3
"""Draws the store icon (512 px) and the feature graphic (1024x500) from the geometry of the adaptive launcher icon.

The numbers are the ones in app/src/main/res/drawable/ic_launcher_foreground.xml (108 dp viewport) and
ic_launcher_background.xml (gradient #0A84FF to #2CC5C0). Output goes to fastlane/metadata/android/en-US/images/.
Needs Pillow. Usage: scripts/gen-store-assets.py [output_dir]
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

TOP, BOTTOM = (0x0A, 0x84, 0xFF), (0x2C, 0xC5, 0xC0)
SS = 4  # supersampling


def bezier(p0, p1, p2, p3, n=48):
    pts = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        pts.append((u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                    u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
    return pts


def pin_outline():
    """Teardrop: tip (54,80), left side up to (38,43), arc of radius 16 over the top, right side back to the tip."""
    import math
    pts = bezier((54, 80), (54, 80), (38, 62), (38, 43))[:-1]
    for k in range(0, 181, 3):  # arc from angle 180 (left) over the top (270 screen) to 0 (right)
        a = math.radians(180 + k)
        pts.append((54 + 16 * math.cos(a), 43 + 16 * math.sin(a)))
    pts += bezier((70, 43), (70, 62), (54, 80), (54, 80))
    return pts


def gradient(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    return img


def icon(size, rounded=True):
    big = size * SS
    base = gradient(big).convert("RGBA")
    s = big / 108.0
    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([(40 * s, 79.4 * s), (68 * s, 86.6 * s)], fill=(0, 0, 0, 72))
    d.polygon([(x * s, y * s) for x, y in pin_outline()], fill=(255, 255, 255, 255))
    d.ellipse([((54 - 6.5) * s, (43 - 6.5) * s), ((54 + 6.5) * s, (43 + 6.5) * s)], fill=(0, 0, 0, 0))
    # punch the hole: the gradient shows through
    hole = Image.new("L", (big, big), 0)
    ImageDraw.Draw(hole).ellipse([((54 - 6.5) * s, (43 - 6.5) * s), ((54 + 6.5) * s, (43 + 6.5) * s)], fill=255)
    alpha = layer.getchannel("A")
    alpha = Image.composite(Image.new("L", (big, big), 0), alpha, hole)
    layer.putalpha(alpha)
    base.alpha_composite(layer)
    if rounded:
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.2237), fill=255)
        base.putalpha(mask)
    return base.resize((size, size), Image.LANCZOS)


def feature(w=1024, h=500):
    img = gradient(max(w, h)).crop((0, 0, w, h)).convert("RGBA")
    img.alpha_composite(Image.new("RGBA", (w, h), (6, 28, 58, 150)))  # darker, so the icon stands out
    ic = icon(240)
    img.alpha_composite(ic, (80, (h - 240) // 2))
    d = ImageDraw.Draw(img)
    def font(sz):
        for f in ("/usr/share/fonts/open-sans/OpenSans-Bold.ttf", "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf",
                  "/usr/share/fonts/liberation-sans/LiberationSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
            if os.path.exists(f):
                return ImageFont.truetype(f, sz)
        return ImageFont.load_default()
    d.text((370, 130), "UltimateMaps", font=font(86), fill=(255, 255, 255, 255))
    d.text((374, 250), "Offline maps and navigation.", font=font(36), fill=(255, 255, 255, 235))
    d.text((374, 306), "Private. No tracking. Open source.", font=font(36), fill=(255, 255, 255, 235))
    return img.convert("RGB")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "fastlane", "metadata", "android", "en-US", "images")
    os.makedirs(out, exist_ok=True)
    icon(512).save(os.path.join(out, "icon.png"), optimize=True)
    feature().save(os.path.join(out, "featureGraphic.png"), optimize=True)
    print("written to", os.path.abspath(out))


if __name__ == "__main__":
    main()
