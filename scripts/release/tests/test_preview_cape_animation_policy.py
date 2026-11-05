from __future__ import annotations

import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]


class PreviewCapeAnimationPolicyTest(unittest.TestCase):
    """The preview draws every frame, so its cape-animation work must stay off the render thread."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.widget = java_source("client/gui/widget/PlayerWidget.java", repository=ROOT).read_text(encoding="utf-8")

    def render_body(self) -> str:
        start = self.widget.index("renderWidget(GuiGraphics graphics")
        return self.widget[start : self.widget.index("// Get current model center", start)]

    def test_preview_never_decodes_a_cape_on_the_render_thread(self) -> None:
        body = self.render_body()
        # The atlas is decoded by the async registration, only for a cape the catalogue lists as
        # animated, and a refused request waits before it is asked again.
        self.assertNotIn("getSourceImage(", body)
        self.assertNotIn("getAnimationMetadata(", body)
        self.assertNotIn("animManager.registerAnimation(", body)
        self.assertIn("mayRequestAnimation(animationId)", body)
        self.assertIn("asset != null && asset.isAnimated()", body)
        self.assertIn("registerAnimationAsync(", body)

    def test_preview_advances_frames_but_not_the_slot_clock(self) -> None:
        body = self.render_body()
        self.assertIn("AnimatedTextureManager.getInstance().tickFrames();", body)
        self.assertNotIn(".tick();", body)


if __name__ == "__main__":
    unittest.main()
