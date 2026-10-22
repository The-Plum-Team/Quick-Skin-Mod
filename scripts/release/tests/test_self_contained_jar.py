from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import verify_release  # noqa: E402


INJECTED = (
    "architectury_inject_quickskin_common_d6d53dbaef6e4a1e8e7b75f2a160bab5_"
    "1eb2ae6b00ecf381d6d7972940b81090bb5b38283499f30a34fc901a16dd2233"
    "QuickSkincommon1201311devjar/PlatformMethods.class"
)
RELOCATED = "com/quickskin/mod/shadow/twelvemonkeys/"


def release_jar(add: str | None = None, remove: str | None = None) -> set[str]:
    """The entry layout of a 3.1.1 release JAR with the relocated WebP decoder."""

    names = {
        "META-INF/",
        "META-INF/MANIFEST.MF",
        "META-INF/mods.toml",
        "META-INF/neoforge.mods.toml",
        "META-INF/accesstransformer.cfg",
        "META-INF/licenses/TwelveMonkeys-LICENSE.txt",
        "com/quickskin/mod/QuickSkin.class",
        INJECTED,
        verify_release.WEBP_READER_CLASS,
        RELOCATED + "imageio/color/icc_profiles_lnx.properties",
        RELOCATED + "net/MIMEUtil.properties",
        "assets/quickskin/lang/en_us.json",
        "data/quickskin/tags/example.json",
        "fabric.mod.json",
        "icon.png",
        "pack.mcmeta",
        "quick-skin.accesswidener",
        "quickskin.mixins.json",
    }
    if add is not None:
        names.add(add)
    if remove is not None:
        names.remove(remove)
    return names


class SelfContainedJarTest(unittest.TestCase):
    def verify(self, names: set[str]) -> None:
        verify_release.verify_self_contained(names, "fixture.jar")

    def test_accepts_only_quick_skin_namespaces(self) -> None:
        self.verify(release_jar())

    def test_rejects_every_way_of_bundling_a_foreign_library(self) -> None:
        foreign = {
            "merged upstream class": "com/luciad/imageio/webp/WebP.class",
            "unrelocated resource": "com/twelvemonkeys/net/MIMEUtil.properties",
            "top-level resource directory": "profiles/ClayRGB1998.icc",
            "foreign asset namespace": "assets/minecraft/textures/entity/player/wide/steve.png",
            "Forge nested library": "META-INF/jarjar/webp-imageio-0.1.6.jar",
            "JarJar metadata": "META-INF/jarjar/metadata.json",
            "Fabric nested library": "META-INF/jars/webp-imageio-0.1.6.jar",
            "nested JAR elsewhere": "com/quickskin/mod/lib/library.jar",
            "native library": "native/linux/64/libwebp-imageio.so",
            "native library inside the namespace": "com/quickskin/mod/natives/webp.dll",
            "macOS native library": "com/quickskin/mod/natives/libwebp.dylib",
            "global ImageIO service": "META-INF/services/javax.imageio.spi.ImageReaderSpi",
            "Maven metadata": "META-INF/maven/org.sejda.imageio/webp-imageio/pom.xml",
            "multi-release class": "META-INF/versions/17/com/example/Library.class",
            "module descriptor": "module-info.class",
            "top-level class": "Library.class",
            "another mod's injected class": (
                "architectury_inject_othermod_common_" + "0" * 32 + "_" + "0" * 64
                + "/PlatformMethods.class"
            ),
        }
        for label, name in foreign.items():
            with self.subTest(label=label):
                with self.assertRaises(verify_release.VerificationError):
                    self.verify(release_jar(add=name))

    def test_requires_the_relocated_decoder_and_its_license(self) -> None:
        for name in (verify_release.WEBP_READER_CLASS, *verify_release.BUNDLED_LICENSES):
            with self.subTest(name=name):
                with self.assertRaises(verify_release.VerificationError):
                    self.verify(release_jar(remove=name))


if __name__ == "__main__":
    unittest.main()
