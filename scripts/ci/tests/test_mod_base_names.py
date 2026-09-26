"""Quick Skin's literal copies of mod-base names equal the pinned kit's single sources.

Feature coverage runs in workflows that never install the kit, so it keeps literal copies of the
Pages workflow, its events, the retained baseline grammar and the job names the jobs API reports.
This test binds every copy to ``mod_base.workflow`` and ``mod_base.model.grammar``.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))
sys.path.insert(0, str(ROOT / "scripts" / "ci" / "tests"))

import mod_base_path  # noqa: E402

mod_base_path.kit_root()

from mod_base import workflow  # noqa: E402
from mod_base.config import load_config  # noqa: E402
from mod_base.model import grammar  # noqa: E402

import feature_coverage_github as publisher  # noqa: E402
import feature_pages  # noqa: E402

KEY_PATTERN = r"mc[0-9]+(?:\.[0-9]+){1,2}"


class ModBaseNamesTest(unittest.TestCase):
    def test_pages_workflow_and_events_are_the_managed_caller(self) -> None:
        self.assertEqual(workflow.PAGES_WORKFLOW_PATH, publisher.PAGES_WORKFLOW)
        self.assertEqual(workflow.PAGES_EVENTS, publisher.PAGES_EVENTS)

    def test_retained_baseline_names_follow_the_kit_grammar(self) -> None:
        self.assertEqual(grammar.baseline_name_regex(KEY_PATTERN), publisher.PUBLIC_BASELINE_NAME.pattern)
        self.assertEqual(grammar.ARTIFACT_PREFIXES["baseline"] + "--", publisher.PUBLIC_BASELINE_PREFIX)
        name = publisher.public_baseline_name("mc1.20.1", "a" * 40, 42)
        self.assertEqual(grammar.baseline_name("mc1.20.1", "a" * 40, 42), name)
        parsed = grammar.parse_artifact_name(name)
        self.assertEqual(("baseline", "mc1.20.1", "a" * 40, 42),
                         (parsed.kind, parsed.key, parsed.commit, parsed.run_id))
        self.assertEqual(("mc1.20.1", "a" * 40, 42), publisher.parse_public_baseline_name(name))
        for foreign in ("pages-full-baseline-mc1.20.1--" + "a" * 40 + "--42",
                        grammar.cache_name("mc1.20.1", "a" * 40), "mb-baseline--master--" + "a" * 40 + "--42"):
            with self.subTest(name=foreign):
                self.assertIsNone(publisher.parse_public_baseline_name(foreign))

    def test_published_bundle_artifact_prefixes_follow_the_kit_grammar(self) -> None:
        # feature_pages.verify_runtime_tree resolves each published bundle through these names.
        self.assertEqual(grammar.handoff_name("mc1.20.1", 3).rsplit("--", 1)[0] + "--a",
                         f"{feature_pages.HANDOFF_PREFIX}mc1.20.1--a")
        self.assertEqual(grammar.collected_name("mc1.20.1"), f"{feature_pages.COLLECTED_PREFIX}mc1.20.1")
        for kind, prefix in (("handoff", feature_pages.HANDOFF_PREFIX), ("collected", feature_pages.COLLECTED_PREFIX)):
            self.assertEqual(grammar.ARTIFACT_PREFIXES[kind] + "--", prefix)

    def test_baseline_owner_jobs_are_the_names_the_jobs_api_reports(self) -> None:
        self.assertEqual(
            (workflow.api_job_name("publish", "build"), workflow.caller_job_name("deploy"),
             workflow.api_job_name("finalize", "refresh", key="mc1.20.1")),
            tuple(name.format(key="mc1.20.1") for name in publisher.PUBLIC_BASELINE_JOBS))

    def test_the_producer_names_the_config_declares(self) -> None:
        config = load_config(ROOT)
        source = config.source
        self.assertEqual(feature_pages.SOURCE_WORKFLOW, source["workflow"])
        self.assertEqual(feature_pages.RUNTIME_SOURCE, source["delegated_reuse_extension"])
        self.assertEqual({feature_pages.RUNTIME_SOURCE, feature_pages.FEATURE_SELECTION}, config.extension_names)
        self.assertEqual("Prepare public evidence for {key} (advisory)", source["handoff_job"])
        self.assertEqual("Upload stable public evidence for this Minecraft target", source["handoff_step"])
        family = config.family("mod-compatibility")
        self.assertEqual("Publish compact compatibility evidence", family["producer"]["job"])
        self.assertEqual("Upload the mod-base family handoff", family["producer"]["step"])


if __name__ == "__main__":
    unittest.main()
