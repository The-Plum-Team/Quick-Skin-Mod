from __future__ import annotations

import io
import json
import sys
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import verify_release  # noqa: E402


WEBP_CLASS = "com/luciad/imageio/webp/WebP.class"
NESTED = "META-INF/jarjar/webp-imageio-0.1.6.jar"


def archive(entries: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        for name, content in entries.items():
            jar.writestr(name, content)
    return buffer.getvalue()


def metadata(group: str = "org.sejda.imageio", path: str = NESTED) -> bytes:
    return json.dumps(
        {
            "jars": [
                {
                    "identifier": {"group": group, "artifact": "webp-imageio"},
                    "version": {"range": "[0.1.6,)", "artifactVersion": "0.1.6"},
                    "path": path,
                }
            ]
        }
    ).encode()


class WebpPackagingTest(unittest.TestCase):
    def verify(self, loader: str, entries: dict[str, bytes]) -> None:
        with zipfile.ZipFile(io.BytesIO(archive(entries))) as jar:
            verify_release.verify_webp_packaging(
                jar, set(jar.namelist()), {"loader": loader}, "fixture.jar"
            )

    def nested(self, **changes: bytes) -> dict[str, bytes]:
        entries = {
            NESTED: archive({WEBP_CLASS: b"class"}),
            "META-INF/jarjar/metadata.json": metadata(),
        }
        entries.update(changes)
        return entries

    def test_fabric_keeps_the_merged_decoder(self) -> None:
        self.verify("fabric", {WEBP_CLASS: b"class"})
        with self.assertRaisesRegex(verify_release.VerificationError, "missing"):
            self.verify("fabric", self.nested())

    def test_fml_requires_the_nested_decoder(self) -> None:
        for loader in ("forge", "neoforge"):
            with self.subTest(loader=loader):
                self.verify(loader, self.nested())

    def test_fml_rejects_a_decoder_merged_into_the_mod_module(self) -> None:
        for name in (
            WEBP_CLASS,
            "native/linux/64/libwebp-imageio.so",
            "META-INF/services/javax.imageio.spi.ImageReaderSpi",
        ):
            with self.subTest(name=name):
                with self.assertRaisesRegex(verify_release.VerificationError, "merged"):
                    self.verify("neoforge", self.nested(**{name: b"merged"}))

    def test_fml_rejects_incomplete_jarjar_metadata(self) -> None:
        broken = {
            "no metadata": {NESTED: archive({WEBP_CLASS: b"class"})},
            "foreign identity": self.nested(
                **{"META-INF/jarjar/metadata.json": metadata(group="org.example")}
            ),
            "missing nested jar": self.nested(
                **{"META-INF/jarjar/metadata.json": metadata(path="META-INF/jarjar/absent.jar")}
            ),
            "empty nested jar": self.nested(**{NESTED: archive({"README": b""})}),
            "malformed metadata": self.nested(**{"META-INF/jarjar/metadata.json": b"{}"}),
        }
        for label, entries in broken.items():
            with self.subTest(label=label):
                with self.assertRaises(verify_release.VerificationError):
                    self.verify("forge", entries)


if __name__ == "__main__":
    unittest.main()
