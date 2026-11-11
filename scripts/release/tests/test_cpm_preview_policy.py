from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]


def strip_java_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", lambda match: "\n" * match.group(0).count("\n"), source, flags=re.DOTALL)
    return re.sub(r"(?<!:)//(?![?]).*", "", source)


def read(relative: str) -> str:
    return strip_java_comments(java_source(relative, repository=ROOT).read_text(encoding="utf-8"))


class CpmPreviewPolicyTest(unittest.TestCase):
    """CPM models drawn on Quick Skin's own preview models, where there is no player.

    CPM draws by replacing the parts of the model it is bound to. A preview model left bound
    would keep CPM's parts and draw the model in every later preview, so each bind is paired
    with a release that runs whatever the draw does, and CPM itself is only ever reached by
    reflection from the one bridge class.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.bridge = read("client/compat/CpmModelPreview.java")
        cls.renderer = read("client/rendering/PlayerModelRenderer.java")
        cls.capture = read("client/rendering/CpmModelCapture.java")

    def test_cpm_is_reached_by_reflection_only(self) -> None:
        for name, source in (("bridge", self.bridge), ("renderer", self.renderer), ("capture", self.capture)):
            self.assertNotRegex(source, r"import\s+com\.tom\.", name)
        # The renderer and the capture name no CPM class at all: only the bridge does
        self.assertNotIn("com.tom.", self.renderer)
        self.assertNotIn("com.tom.", self.capture)
        self.assertIn('Class.forName("com.tom.cpm.api.IClientAPI")', self.bridge)

    def test_the_bridge_is_optional_and_fails_locally(self) -> None:
        # Without CPM nothing is looked up, and a model that cannot be handed back stops the
        # bridge for good instead of leaving CPM's parts on a preview model
        self.assertIn("if (!CPMCompatIntegration.isAvailable()) {", self.bridge)
        release = self.bridge[self.bridge.index("public static void unbind()"):]
        release = release[:release.index("public static boolean isBound()")]
        self.assertIn("unavailable = true;", release)
        self.assertIn("bound = Bound.NOTHING;", release)

    def test_the_players_own_model_is_loaded_for_the_local_account(self) -> None:
        # CPM holds every profile but the local account's to its limits for strangers' models
        self.assertIn("h.getPlayerIdObject.invoke(h.clientAccess)", self.bridge)
        self.assertIn("BoundedFileReader.readBytes(file, MAX_MODEL_FILE_BYTES)", self.bridge)
        self.assertIn("ClientIoExecutor.runAsync(", self.bridge)

    def test_every_bind_is_released_in_a_finally(self) -> None:
        for name, source in (("renderer", self.renderer), ("capture", self.capture)):
            binds = len(re.findall(r"CpmModelPreview\.bind\(", source))
            self.assertGreater(binds, 0, name)
            # Each release sits in a finally block: "} finally {" ... "CpmModelPreview.unbind();"
            released = len(re.findall(r"\}\s*finally\s*\{[^{}]*?(?:\{[^{}]*\}[^{}]*?)*CpmModelPreview\.unbind\(\);",
                                      source))
            self.assertGreater(released, 0, name)
        # The inline draw before 1.21.6 (two API families) and the picture-in-picture draw, whose
        # model is also released at once when the pass has drawn the skin; then the capture's two
        # families
        self.assertEqual(4, self.renderer.count("CpmModelPreview.unbind();"))
        self.assertIn("if (!pending.skinHidden()) {", self.renderer)
        self.assertGreaterEqual(self.capture.count("CpmModelPreview.unbind();"), 2)

    def test_the_picture_in_picture_pass_draws_the_bound_model_itself(self) -> None:
        # The pass is handed a model that draws nothing; the mixin draws CPM's
        for mixin in ("common/src/main/java/com/quickskin/mod/mixin/GuiSkinRendererMixin.java",
                      "neoforge/src/main/java/com/quickskin/mod/neoforge/mixin/GuiSkinRendererMixin.java"):
            source = strip_java_comments((ROOT / mixin).read_text(encoding="utf-8"))
            self.assertEqual(3, source.count("PlayerModelRenderer.renderPendingCpmModel("), mixin)
        self.assertIn("PlayerModel submitted = cpmReplacesSkin ? hiddenModel : model;", self.renderer)
        self.assertIn("cpmReplacesSkin ? new ModelPart(java.util.List.of(), java.util.Map.of()) : model.root()",
                      self.renderer)
        # From 1.21.9 CPM's parts start from the rest pose: the preview's pose is copied on
        self.assertIn("bound[i].loadPose(pose[i]);", self.renderer)

    def test_the_elytra_view_keeps_the_skin(self) -> None:
        self.assertIn("return playerData.isElytraPreview() ? null : playerData.getCpmModelFile();", self.renderer)


if __name__ == "__main__":
    unittest.main()
