#!/usr/bin/env python3
"""Generate density-specific WOLF AI launcher icons from the approved app logo."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path("app/src/main/res")
SOURCE = ROOT / "drawable-nodpi" / "wolf_logo_photo.jpg"
if not SOURCE.is_file():
    raise SystemExit(f"Missing launcher source image: {SOURCE}")

logo = Image.open(SOURCE).convert("RGBA")
# The source is square, but center-crop defensively if it is ever replaced.
side = min(logo.width, logo.height)
left = (logo.width - side) // 2
top = (logo.height - side) // 2
logo = logo.crop((left, top, left + side, top + side))

densities = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}
for density, scale in densities.items():
    folder = ROOT / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)

    # Legacy launcher icon: full square image at the platform's expected size.
    px = round(48 * scale)
    square = logo.resize((px, px), Image.Resampling.LANCZOS)
    square.save(folder / "ic_launcher.png", optimize=True)

    # Round launcher icon for launchers that explicitly request roundIcon.
    circle = Image.new("L", (px, px), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, px - 1, px - 1), fill=255)
    round_icon = square.copy()
    round_icon.putalpha(circle)
    round_icon.save(folder / "ic_launcher_round.png", optimize=True)

    # Adaptive foreground canvas is 108 dp. Keep the actual logo inside
    # the central 72 dp safe zone so Android/HyperOS masks cannot crop it.
    adaptive_px = round(108 * scale)
    safe_px = round(72 * scale)
    foreground = Image.new("RGBA", (adaptive_px, adaptive_px), (0, 0, 0, 0))
    mark = logo.resize((safe_px, safe_px), Image.Resampling.LANCZOS)
    offset = (adaptive_px - safe_px) // 2
    foreground.alpha_composite(mark, (offset, offset))
    foreground.save(folder / "wolf_launcher_foreground.png", optimize=True)

print("Generated WOLF AI launcher icons for mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi.")
