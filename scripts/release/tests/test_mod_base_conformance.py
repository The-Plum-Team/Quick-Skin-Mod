"""mod-base ``conformance`` on Quick Skin's real release matrix, scenario contract and lock.

The pinned kit simulates complete generations for the anchor key ``mc1.20.1`` (Fabric and Forge,
the lossless anchor, every optional-mod lane) and the newest no-remap key ``mc26.3`` (every
optional mod not applicable), with the ``mod-compatibility`` family, through its own simulated
GitHub: producer, admission, collection, build, refresh, rotations and later generations whose
family legs are carried forward, a handoff reusing a pull-request execution whose
``quick-skin.runtime_source`` the adapter authenticates against seeded ``ci_reuse`` artifacts, and
a selective hud-preview generation one commit after the published baseline, admitted from that
real Git diff and composed with the baseline its seeded coverage certificate names. A skipped
variant does not fail the run, so the variants Quick Skin expects are asserted here, with the
documented reason for each one it cannot run.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci" / "tests"))

import mod_base_path  # noqa: E402

KEYS = ("mc1.20.1", "mc26.3")
COMMAND = [sys.executable, "scripts/ci/mod_base_kit.py", "run", "conformance", "--repo", ".",
           "--keys", ",".join(KEYS), "--families"]
#: Below the 30-minute budget of the required "Validate release policy" job (build-gate.yml), so a
#: hung simulation fails here with its stderr tail instead of the job being killed silently. The
#: simulation took about 8-9 minutes on an idle Apple M5 Pro and up to 20 minutes under load.
TIMEOUT_SECONDS = 1500


class ModBaseConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        mod_base_path.kit_root()
        try:
            completed = subprocess.run(COMMAND, cwd=ROOT, env=mod_base_path.child_environment(), capture_output=True,
                                       text=True, timeout=TIMEOUT_SECONDS, check=False)
        except subprocess.TimeoutExpired as exc:
            tail = exc.stderr.decode("utf-8", "replace") if isinstance(exc.stderr, bytes) else exc.stderr or ""
            raise AssertionError(f"conformance exceeded {TIMEOUT_SECONDS} s: {tail[-4000:]}") from None
        if completed.returncode != 0:
            raise AssertionError(f"conformance exited {completed.returncode}: {completed.stderr[-4000:]}")
        cls.report = json.loads(completed.stdout)

    def test_every_key_is_published_with_its_contracted_frames_and_the_anchor(self) -> None:
        keys = {entry["key"]: entry for entry in self.report["keys"]}
        self.assertEqual(set(KEYS), set(keys))
        for key in KEYS:
            with self.subTest(key=key):
                self.assertEqual({"key": key, "lanes": 14, "frames": 180, "comparisons": 76, "scope": "complete",
                                  "anchor": key == "mc1.20.1"}, keys[key])
        self.assertEqual(360, sum(entry["frames"] for entry in keys.values()))
        self.assertEqual("The-Plum-Team/Quick-Skin-Mod", self.report["repository"])

    def test_the_compatibility_family_is_available_and_carried_across_documentation_commits(self) -> None:
        self.assertEqual({("mod-compatibility", key, "available") for key in KEYS},
                         {(entry["family"], entry["key"], entry["status"]) for entry in self.report["families"]})
        variants = self.report["variants"]
        self.assertEqual("passed", variants["carried"])
        self.assertEqual("passed", variants["family-outcomes"])
        for generation in self.report["site"]["generations"]:
            self.assertNotIn("unavailable", generation["family_legs"])

    def test_delegated_reuse_is_authenticated_by_the_adapter(self) -> None:
        # mod_base_fixtures.delegated_extensions seeds the tested-source seal and the reused-source
        # descriptor; the adapter's authenticate_extensions downloads and binds the descriptor.
        self.assertEqual("passed", self.report["variants"]["delegated"])
        self.assertIn("authenticate_extensions", self.report["hooks"])

    def test_a_real_selection_is_composed_with_its_certified_baseline(self) -> None:
        # mod_base_fixtures.selected_extensions admits the diff from the baseline commit to the
        # kit's selected head (SELECTED_CHANGE, a hud-preview source): its two HUD checkpoints
        # re-capture two lanes of mc1.20.1 partly, and every other lane stays the baseline's.
        self.assertEqual("passed", self.report["variants"]["selected"])
        self.assertEqual({"baseline": 12, "mixed": 2, "selected": 0}, self.report["site"]["composed_lanes"])
        self.assertIn("compose", self.report["hooks"])

    def test_skipped_variants_are_exactly_the_documented_ones(self) -> None:
        self.assertEqual({
            "attested": "skipped: config.source.attestation_job is null",
            "newest-run": "skipped: config.source.require_newest_run is false",
        }, {name: value for name, value in self.report["variants"].items() if value.startswith("skipped")})

    def test_hooks_budgets_and_site_facts(self) -> None:
        self.assertTrue({"targets", "expectation", "collect", "anchor_selection", "family_validate",
                         "verify_publication"} <= set(self.report["hooks"]))
        site = self.report["site"]
        self.assertTrue(site["node_check"])
        self.assertEqual(360, site["frames"])
        self.assertLessEqual(site["max_job_reads"], 160)
        self.assertEqual([2, 3, 4], [generation["generation"] for generation in site["generations"]])
        self.assertGreater(self.report["checks"], 0)
        self.assertTrue(all(entry.split(":", 1)[0] in {"recovery", "manual", "deploy", "family"}
                            for entry in self.report["admission"]))


if __name__ == "__main__":
    unittest.main()
