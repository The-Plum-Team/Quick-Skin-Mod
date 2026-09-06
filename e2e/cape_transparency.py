"""Measure the authored plaid underlay in the existing worn-cape capture."""
from __future__ import annotations

from statistics import mean

from PIL import Image

from scenario_contract import TranslucentCapeProbe


def inspect_cape_underlay(image: Image.Image, probe: TranslucentCapeProbe) -> dict:
    """Reject an opaque panel, a missing cape, and an unblended/fully transparent face.

    The fixture has a yellow border, a uniform half-alpha teal front, and an opaque back.
    Only the player's plaid can produce repeated horizontal bands inside that teal front.
    Locate the border in the authored player region, then sample well inside it so neither
    its edges nor the visible arms/legs can impersonate the underlay. No image is changed.
    """
    rgb = image.convert("RGB")
    left, top, right, bottom = (
        round(value * (rgb.width if index % 2 == 0 else rgb.height))
        for index, value in enumerate(probe.region)
    )
    pixels = rgb.load()
    border = []
    for y in range(top, bottom):
        for x in range(left, right):
            r, g, b = pixels[x, y]
            if r >= 80 and g >= 60 and 1.05 * g <= r <= 1.45 * g and b < 0.45 * g:
                border.append((x, y))
    if not border:
        raise ValueError("translucent cape yellow border is missing")
    x0, x1 = min(x for x, _ in border), max(x for x, _ in border) + 1
    y0, y1 = min(y for _, y in border), max(y for _, y in border) + 1
    width, height = x1 - x0, y1 - y0
    if not (rgb.width / 30 <= width <= rgb.width / 8
            and rgb.height / 12 <= height <= rgb.height / 3
            and 1.3 <= height / width <= 2.0
            and len(border) >= width * height * 0.10):
        raise ValueError("translucent cape yellow border has unexpected geometry")

    rows = []
    teal = samples = 0
    for y in range(y0 + height // 8, y0 + height * 3 // 4):
        row = []
        for x in range(x0 + width // 4, x0 + width * 3 // 4):
            r, g, b = pixels[x, y]
            teal += g >= r + 20 and b >= r + 20 and g <= b + 20
            samples += 1
            row.append(0.2126 * r + 0.7152 * g + 0.0722 * b)
        rows.append(mean(row))
    if teal / samples < 0.90:
        raise ValueError("translucent cape front is missing its blended teal colour")
    ordered = sorted(rows)
    low, high = ordered[len(rows) // 5], ordered[len(rows) * 4 // 5]
    contrast = high - low
    if contrast < probe.minimum_contrast:
        raise ValueError(f"translucent cape hides the plaid underlay: contrast={contrast:.2f}, "
                         f"required>={probe.minimum_contrast:.2f}")

    # Broad alternating bands distinguish plaid from a lighting gradient or single-pixel noise.
    midpoint = (low + high) / 2
    margin = probe.minimum_contrast / 4
    minimum_run = max(3, height // 64)
    stable = []
    current = None
    count = 0
    for value in rows:
        band = 0 if value <= midpoint - margin else 1 if value >= midpoint + margin else None
        if band is None or band != current:
            current, count = band, 0
        count += 1
        if band is not None and count == minimum_run and (not stable or stable[-1] != band):
            stable.append(band)
    changes = max(0, len(stable) - 1)
    if changes < probe.minimum_band_changes:
        raise ValueError(f"translucent cape lacks alternating plaid bands: changes={changes}, "
                         f"required>={probe.minimum_band_changes}")
    return {"border_box": [x0, y0, x1, y1], "contrast": round(contrast, 3),
            "band_changes": changes, "teal_fraction": round(teal / samples, 3)}
