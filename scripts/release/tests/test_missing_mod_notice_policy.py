"""Source contracts of the message for players who join without Quick Skin (#2133).

The server sends that message as plain text from its own language files, because the player's game
has none of Quick Skin's translations. These checks keep every language able to say it and keep
the mod's name searchable in it.
"""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
LANG_DIRECTORY = ROOT / "common" / "src" / "main" / "resources" / "assets" / "quickskin" / "lang"
NOTICE_TEXT = (
    ROOT
    / "modules"
    / "server-appearance"
    / "src"
    / "main"
    / "java"
    / "com"
    / "quickskin"
    / "mod"
    / "server"
    / "vanilla"
    / "MissingModNoticeText.java"
)
KEY = "quickskin.server.missing_mod_notice"


class MissingModNoticeTranslationsTest(unittest.TestCase):
    def test_every_language_has_the_notice_with_the_mod_name_untranslated(self) -> None:
        english = json.loads((LANG_DIRECTORY / "en_us.json").read_text(encoding="utf-8"))[KEY]
        locales = sorted(LANG_DIRECTORY.glob("*.json"))
        self.assertEqual(25, len(locales))
        for path in locales:
            with self.subTest(locale=path.name):
                text = json.loads(path.read_text(encoding="utf-8")).get(KEY)
                self.assertIsInstance(text, str, f"{path.name} lacks {KEY}")
                # The player has to search for it by this name to install it.
                self.assertTrue(text.startswith("[Quick Skin] "), text)
                self.assertIn("Quick Skin", text[len("[Quick Skin] ") :])
                self.assertNotIn("%", text, "sent as plain text: nothing is substituted")
                self.assertLessEqual(len(text), 512)
                if path.name != "en_us.json":
                    self.assertNotEqual(english, text)

    def test_the_built_in_english_matches_en_us(self) -> None:
        english = json.loads((LANG_DIRECTORY / "en_us.json").read_text(encoding="utf-8"))[KEY]
        source = NOTICE_TEXT.read_text(encoding="utf-8")
        match = re.search(r'BUILT_IN_ENGLISH = ((?:"[^"]*"\s*\+?\s*)+);', source)
        self.assertIsNotNone(match)
        assert match is not None
        built_in = "".join(re.findall(r'"([^"]*)"', match.group(1)))
        self.assertEqual(english, built_in)


if __name__ == "__main__":
    unittest.main()
