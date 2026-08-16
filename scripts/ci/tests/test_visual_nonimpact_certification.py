from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from visual_nonimpact_certification import (  # noqa: E402
    CertificationError,
    create_certificate,
    validate_certificate,
)


class VisualNonimpactCertificationTest(unittest.TestCase):
    def certificate(self) -> dict[str, object]:
        return create_certificate(
            master_source_sha="1" * 40,
            anchor_branch="forge-and-fabric-1.20.1",
            anchor_source_branch="automation/sync/forge-and-fabric-1.20.1/1",
            anchor_base_sha="2" * 40,
            anchor_source_sha="3" * 40,
            anchor_target_sha="4" * 40,
            build_run_id=10,
            e2e_run_id=11,
            impact_policy_sha256="5" * 64,
            impact={
                "schema_version": 1,
                "scope": "replicated-port",
                "review_required": False,
                "paths": [
                    ".github/workflows/mod-compatibility-review.yml",
                    "docs/ai/PROJECT.md",
                ],
            },
        )

    def test_clean_nonvisual_anchor_is_exactly_bound(self) -> None:
        certificate = self.certificate()
        self.assertEqual(
            certificate,
            validate_certificate(
                certificate,
                expected_master_sha="1" * 40,
                expected_anchor_branch="forge-and-fabric-1.20.1",
            ),
        )

    def test_review_required_or_untrusted_source_is_rejected(self) -> None:
        certificate = self.certificate()
        certificate["impact"]["review_required"] = True  # type: ignore[index]
        with self.assertRaisesRegex(CertificationError, "still requires"):
            validate_certificate(certificate)

        certificate = self.certificate()
        certificate["anchor_source_branch"] = "feature/not-automation"
        with self.assertRaisesRegex(CertificationError, "not an automatic"):
            validate_certificate(certificate)

    def test_tampering_and_incomplete_path_coverage_are_rejected(self) -> None:
        for mutate, message in (
            (
                lambda value: value.update(master_source_sha="z" * 40),
                "commit SHA",
            ),
            (
                lambda value: value["impact"].update(paths=[]),  # type: ignore[union-attr]
                "path coverage",
            ),
            (
                lambda value: value["impact"].update(  # type: ignore[union-attr]
                    paths=["docs/z.md", "docs/a.md"]
                ),
                "sorted and unique",
            ),
        ):
            certificate = self.certificate()
            mutate(certificate)
            with self.subTest(message=message), self.assertRaisesRegex(
                CertificationError, message
            ):
                validate_certificate(certificate)


if __name__ == "__main__":
    unittest.main()
