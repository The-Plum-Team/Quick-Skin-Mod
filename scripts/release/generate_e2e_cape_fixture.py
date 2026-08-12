#!/usr/bin/env python3
"""Regenerate the tiny deterministic animated cape used by packaged E2E."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "common/src/e2e/resources/qs_e2e_test_cape.gif"


def cape_frame(color: tuple[int, int, int], motif_x: int) -> Image.Image:
    image = Image.new("RGB", (64, 32), (18, 22, 34))
    draw = ImageDraw.Draw(image)
    # Minecraft's visible rear cape face in the canonical 64x32 UV atlas.
    draw.rectangle((1, 1, 10, 16), fill=(238, 238, 242))
    draw.line((1, 1, 10, 1), fill=(10, 10, 10))
    draw.line((1, 16, 10, 16), fill=(10, 10, 10))
    # A deliberately chunky landmark that remains unmistakable after model sampling.
    draw.rectangle((motif_x, 5, motif_x + 2, 12), fill=color)
    return image


def generate(target: Path = TARGET) -> None:
    frames = [
        cape_frame((220, 35, 45), 2),
        cape_frame((30, 145, 230), 7),
    ]
    frames[0].save(
        target,
        format="GIF",
        save_all=True,
        append_images=frames[1:],
        duration=(400, 400),
        loop=0,
        disposal=2,
        optimize=False,
    )


def main() -> None:
    generate()


if __name__ == "__main__":
    main()
