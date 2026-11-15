from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
JAVA_COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


class CatalogueScanPolicyTest(unittest.TestCase):
    """One entry the catalogue cannot read never ends its scan, and with it the game's start."""

    def test_the_catalogue_scans_skip_what_they_cannot_read(self) -> None:
        source = JAVA_COMMENT.sub(
            "", java_source("client/services/LocalAssetManager.java", repository=ROOT).read_text(encoding="utf-8")
        )
        # The stream of Files.walk fails as a whole on one unreadable entry
        self.assertNotIn("Files.walk(", source)
        self.assertEqual(3, source.count("= scanCandidates("), "two CPM scans and the skin and cape scan")
        self.assertIn("BoundedFileWalk.regularFiles(directory, MAX_SCAN_DEPTH, MAX_SCAN_CANDIDATES)", source)


if __name__ == "__main__":
    unittest.main()
