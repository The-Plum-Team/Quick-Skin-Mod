from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
JAVA_COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)


def code(name: str) -> str:
    return JAVA_COMMENT.sub("", java_source(name, repository=ROOT).read_text(encoding="utf-8"))


class WorldEntryPolicyTest(unittest.TestCase):
    """A failure of Quick Skin's in a player callback never keeps a world from loading."""

    def assert_guarded(self, source: str, event: str) -> None:
        registrations = re.findall(rf"\b{re.escape(event)}\.register\(([^;{{]*?->\s*)(\S+)", source)
        self.assertTrue(registrations, f"{event} is not registered")
        for _, body in registrations:
            self.assertTrue(
                body.startswith("GameCallbacks.guarded("),
                f"{event} runs Quick Skin's code unguarded inside the game's own handler",
            )

    def test_the_client_session_callbacks_cannot_throw_into_the_game(self) -> None:
        source = code("event/ClientEvents.java")
        for event in ("CLIENT_PLAYER_JOIN", "CLIENT_PLAYER_QUIT", "CLIENT_PLAYER_RESPAWN"):
            with self.subTest(event=event):
                self.assert_guarded(source, f"ClientPlayerEvent.{event}")

    def test_the_server_player_callbacks_cannot_throw_into_the_game(self) -> None:
        source = code("event/CommonEvents.java")
        for event in ("PLAYER_JOIN", "PLAYER_QUIT", "PLAYER_RESPAWN", "CHANGE_DIMENSION"):
            with self.subTest(event=event):
                self.assert_guarded(source, f"PlayerEvent.{event}")

    def test_the_guard_logs_and_swallows_only_quick_skins_own_failures(self) -> None:
        source = code("event/GameCallbacks.java")
        self.assertRegex(source, r"catch \(RuntimeException \| LinkageError \w+\)")
        self.assertIn("LOGGER.error(", source)
        self.assertNotIn("Throwable", source)


if __name__ == "__main__":
    unittest.main()
