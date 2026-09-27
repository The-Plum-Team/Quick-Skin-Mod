"""Quick Skin's contract with the managed mod-base Pages caller (mod-base SPEC §5.2, §5.7).

``.github/workflows/pages.yml`` is the kit's managed region, rendered with this repository's single
pin, followed by one mod-local extension job. The kit's own tests own the behaviour of the callee
workflows; these tests pin what this repository's copy must keep: byte equality with the pinned
template, the single pin, the Pillow lockstep, the extension-region shape, the per-job grants, the
forbidden legacy wake shapes, the caller-owned deploy, the two kit locks and the hourly recovery.
Other policy tests reuse the helpers below instead of re-parsing the caller.
"""

from __future__ import annotations

import importlib.util
import re
import sys
import unittest
from functools import lru_cache
from pathlib import Path
from types import ModuleType

import mod_base_path

ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"
CALLER_PATH = WORKFLOWS / "pages.yml"
BOOTSTRAP_PATH = ROOT / "scripts" / "ci" / "mod_base_kit.py"
PAGES_REQUIREMENTS = ROOT / "scripts" / "pages" / "requirements.txt"

MANAGED_BEGIN = "# >>> mod-base managed:"
MANAGED_END = "# <<< mod-base managed\n"
EXTENSION_BEGIN = "# >>> mod-local extensions:"
EXTENSION_END = "# <<< mod-local extensions\n"
PIN_LINE = re.compile(
    r"^\s*(?:-\s+)?uses:\s+The-Plum-Team/mod-base/(\S+)@([0-9a-f]{40})\s+#\s+(v\d+\.\d+\.\d+)\s*$",
    re.MULTILINE,
)
KIT_MENTION = re.compile(r"the-plum-team/mod-base", re.IGNORECASE)

#: Every caller job and exactly the permissions it grants (its callee's needs, and no more).
CALLER_PERMISSIONS = {
    "verify-kit": {"actions": "read", "contents": "read"},
    "publish": {"actions": "read", "contents": "read"},
    "deploy": {"contents": "read", "pages": "write", "id-token": "write"},
    "finalize": {"actions": "read", "contents": "read"},
    "request-rotation": {"actions": "write"},
    "rotate": {"actions": "write", "contents": "read"},
}
#: Quick Skin's only extension job: the pre-existing feature-coverage request (SPEC §5.7). Its
#: contents: write sends the non-Pages feature-coverage-requested repository_dispatch.
EXTENSION_PERMISSIONS = {"ext-feature-coverage": {"actions": "read", "contents": "write"}}
CALLEE_USES = {
    "publish": ".github/workflows/publish.yml",
    "finalize": ".github/workflows/finalize.yml",
    "rotate": ".github/workflows/rotate.yml",
}
#: Legacy wake shapes that must never return (SPEC §5.1).
FORBIDDEN_CALLER_TEXT = (
    "github.event.workflow_run",
    "implementation_sha",
    "pull_request_target",
    "repository_dispatch",
    "quick-skin-pages-wake",
)
PUBLICATION_LOCK = "mod-base-pages-publication"
ROTATION_LOCK = "mod-base-pages-rotation"
RECOVERY_CRON = '    - cron: "43 * * * *"\n'
DEPLOY_PAGES = "actions/deploy-pages@cd2ce8fcbc39b97be8ca5fce6e763baed58fa128 # v5.0.0"
#: The workflows that carry the pin, and how many pin lines each holds.
PIN_REFERENCES = {
    ".github/workflows/build-gate.yml": 3,
    ".github/workflows/mod-compatibility-review.yml": 2,
    ".github/workflows/on-demand-e2e.yml": 2,
    ".github/workflows/pages.yml": 3,
}


def caller_text() -> str:
    return CALLER_PATH.read_text(encoding="utf-8")


def caller_regions(text: str | None = None) -> tuple[str, str, str]:
    """``(managed region, extension begin line, extension body)`` of the caller.

    The managed region runs from the first line to the managed end marker; the extension region
    must follow immediately and end the file.
    """

    text = caller_text() if text is None else text
    lines = text.splitlines(keepends=True)
    if not lines or not lines[0].startswith(MANAGED_BEGIN):
        raise AssertionError("pages.yml must start with the mod-base managed marker")
    ends = [index for index, line in enumerate(lines) if line == MANAGED_END]
    begins = [index for index, line in enumerate(lines) if line.startswith(EXTENSION_BEGIN)]
    closes = [index for index, line in enumerate(lines) if line == EXTENSION_END]
    if len(ends) != 1 or len(begins) != 1 or len(closes) != 1:
        raise AssertionError("each pages.yml region marker must appear exactly once")
    end, begin, close = ends[0], begins[0], closes[0]
    if begin != end + 1 or close != len(lines) - 1:
        raise AssertionError("the extension region must follow the managed region and end the file")
    return "".join(lines[: end + 1]), lines[begin], "".join(lines[begin + 1: close])


def caller_jobs(text: str | None = None) -> list[str]:
    """Every job id of the caller, in file order."""

    text = caller_text() if text is None else text
    return re.findall(r"(?m)^  ([A-Za-z0-9_-]+):\n", text.split("\njobs:\n", 1)[1])


def caller_job(job: str, text: str | None = None) -> str:
    """The block of one caller job (its id line through the line before the next job or marker)."""

    text = caller_text() if text is None else text
    match = re.search(rf"(?ms)^  {re.escape(job)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|^# |\Z)", text)
    if match is None:
        raise AssertionError(f"missing caller job {job}")
    return match.group(0)


def job_permissions(block: str) -> dict[str, str]:
    """The block-mapping ``permissions:`` of one job (4-space job keys, 6-space entries)."""

    match = re.search(r"(?m)^    permissions:\n((?:      [a-z-]+: [a-z]+\n)+)", block)
    if match is None:
        raise AssertionError("job has no block-mapping permissions")
    return dict(re.findall(r"(?m)^      ([a-z-]+): ([a-z]+)$", match.group(1)))


def load_bootstrap() -> ModuleType:
    """The managed bootstrap, loaded by path under a private module name."""

    name = "_quick_skin_caller_test_bootstrap"
    loaded = sys.modules.get(name)
    if loaded is not None:
        return loaded
    spec = importlib.util.spec_from_file_location(name, BOOTSTRAP_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError(f"cannot load {BOOTSTRAP_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


@lru_cache(maxsize=1)
def pin() -> tuple[str, str, tuple[str, ...]]:
    """``(sha, version, references)`` of the repository's single mod-base pin."""

    parsed = load_bootstrap().parse_pin(ROOT)
    return parsed.sha, parsed.version, tuple(parsed.references)


def rendered_managed_region() -> str:
    """The pinned kit's caller template, managed region only, with the pin substituted."""

    template = (mod_base_path.kit_root() / "template" / "managed" / ".github" / "workflows"
                / "pages.yml").read_text(encoding="utf-8")
    managed, _begin, body = caller_regions(template)
    if body:
        raise AssertionError("the kit caller template must carry an empty extension region")
    sha, version, _references = pin()
    return managed.replace("{{PIN}}", sha).replace("{{VERSION}}", version)


def pinned_requirement(path: Path) -> tuple[str, frozenset[str]]:
    """``(version, sha256 hashes)`` of the single ``Pillow==`` requirement in ``path``."""

    text = path.read_text(encoding="utf-8")
    versions = re.findall(r"(?m)^Pillow==([0-9][0-9A-Za-z.]*)\b", text)
    if len(versions) != 1:
        raise AssertionError(f"{path} must pin exactly one Pillow version")
    return versions[0], frozenset(re.findall(r"--hash=sha256:([0-9a-f]{64})", text))


class ModBaseCallerTest(unittest.TestCase):
    def test_managed_region_is_the_pinned_kit_template_byte_for_byte(self) -> None:
        managed, begin, _body = caller_regions()
        self.assertEqual(rendered_managed_region(), managed)
        template = (mod_base_path.kit_root() / "template" / "managed" / ".github" / "workflows"
                    / "pages.yml").read_text(encoding="utf-8")
        self.assertEqual(caller_regions(template)[1], begin)
        # Nothing may precede the managed region or follow the extension region.
        self.assertTrue(caller_text().startswith(MANAGED_BEGIN))
        self.assertTrue(caller_text().endswith(EXTENSION_END))

    def test_every_mod_base_reference_carries_the_single_released_pin(self) -> None:
        sha, version, references = pin()
        self.assertRegex(sha, r"^[0-9a-f]{40}$")
        self.assertRegex(version, r"^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$")
        counted: dict[str, int] = {}
        for reference in references:
            path, _line = reference.rsplit("@", 1)
            counted[path] = counted.get(path, 0) + 1
        self.assertEqual(PIN_REFERENCES, counted)
        for path in sorted(WORKFLOWS.glob("*.y*ml")):
            text = path.read_text(encoding="utf-8")
            pins = set((match.group(2), match.group(3)) for match in PIN_LINE.finditer(text))
            with self.subTest(workflow=path.name):
                self.assertLessEqual(pins, {(sha, version)})
        managed, _begin, body = caller_regions()
        uses = {match.group(1) for match in PIN_LINE.finditer(managed)}
        self.assertEqual(set(CALLEE_USES.values()), uses)
        self.assertIsNone(KIT_MENTION.search(body))

    def test_pages_image_dependency_stays_in_lockstep_with_the_kit(self) -> None:
        kit_version, kit_hashes = pinned_requirement(
            mod_base_path.kit_root() / "requirements" / "pillow.txt")
        mod_version, mod_hashes = pinned_requirement(PAGES_REQUIREMENTS)
        self.assertEqual(kit_version, mod_version)
        self.assertTrue(mod_hashes)
        self.assertLessEqual(mod_hashes, kit_hashes)

    def test_extension_region_holds_only_the_feature_coverage_request(self) -> None:
        mod_base_path.kit_root()
        from mod_base.template import tool

        _managed, _begin, body = caller_regions()
        self.assertEqual([], tool.extension_violations(body.splitlines(keepends=True)))
        jobs = re.findall(r"(?m)^  ([A-Za-z0-9_-]+):\n", body)
        self.assertEqual(list(EXTENSION_PERMISSIONS), jobs)
        for job in jobs:
            self.assertRegex(job, r"^ext-[a-z0-9-]+$")
        extension = caller_job("ext-feature-coverage")
        self.assertEqual(EXTENSION_PERMISSIONS["ext-feature-coverage"], job_permissions(extension))
        self.assertIn("name: Request feature coverage after public baseline retention\n", extension)
        self.assertIn("group: quick-skin-feature-baseline-request-${{ github.sha }}", extension)
        self.assertIn("cancel-in-progress: false", extension)
        self.assertRegex(extension, r"(?m)^      queue: max$")
        self.assertIn("continue-on-error: true", extension)
        self.assertIn("      - publish\n      - finalize\n      - request-rotation\n", extension)
        self.assertNotRegex(extension, r"(?m)^\s+- rotate$")
        self.assertIn("needs.publish.outputs.eligible == 'true'", extension)
        self.assertIn("needs.finalize.result == 'success'", extension)
        self.assertIn("ref: ${{ github.sha }}", extension)
        self.assertIn("persist-credentials: false", extension)
        self.assertIn('[[ "$(git rev-parse HEAD)" != "$GITHUB_SHA" ]]', extension)
        self.assertIn("scripts/release/release_sources.py --kind mode", extension)
        self.assertIn("python3 scripts/ci/feature_coverage_request.py --producer-run-id", extension)
        for forbidden in ("pages: write", "id-token", "actions: write", "github-pages", "mod-base-pages-"):
            self.assertNotIn(forbidden, extension)

    def test_caller_jobs_grant_exactly_their_callee_permissions(self) -> None:
        text = caller_text()
        self.assertIn("\npermissions: {}\n", text.split("\njobs:\n", 1)[0])
        self.assertEqual([*CALLER_PERMISSIONS, *EXTENSION_PERMISSIONS], caller_jobs())
        for job, expected in CALLER_PERMISSIONS.items():
            with self.subTest(job=job):
                self.assertEqual(expected, job_permissions(caller_job(job)))
        sha, version, _references = pin()
        for job, callee in CALLEE_USES.items():
            with self.subTest(callee=job):
                block = caller_job(job)
                self.assertIn(f"    uses: The-Plum-Team/mod-base/{callee}@{sha} # {version}\n", block)
                self.assertIn("needs.verify-kit.outputs.kit_sha", block)
                self.assertNotIn("secrets:", block)

    def test_legacy_wakes_and_unbound_implementations_never_appear(self) -> None:
        text = caller_text()
        for forbidden in FORBIDDEN_CALLER_TEXT:
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, text)
        triggers = text.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(["schedule", "workflow_dispatch"], re.findall(r"(?m)^  ([a-z_]+):", triggers))
        self.assertNotIn("workflows:", triggers)
        self.assertIn("options: [manual, deploy, family, rotate]", triggers)

    def test_only_the_caller_deploy_mints_pages_and_oidc_tokens_without_a_checkout(self) -> None:
        text = caller_text()
        deploy = caller_job("deploy")
        self.assertEqual(1, text.count("pages: write"))
        self.assertEqual(1, text.count("id-token: write"))
        self.assertIn("pages: write", deploy)
        self.assertIn("id-token: write", deploy)
        self.assertNotIn("actions/checkout@", deploy)
        self.assertNotIn("The-Plum-Team/mod-base", deploy)
        self.assertIn("name: github-pages", deploy)
        self.assertIn(f"uses: {DEPLOY_PAGES}", deploy)
        self.assertEqual(1, text.count("actions/deploy-pages@"))
        self.assertIn("Recheck every published source head immediately before deployment", deploy)
        self.assertIn("retaining the deployed site", deploy)
        for job in ("verify-kit", "request-rotation"):
            with self.subTest(job=job):
                self.assertNotIn("actions/checkout@", caller_job(job))

    def test_publication_and_rotation_use_separate_kit_locks(self) -> None:
        header = caller_text().split("\njobs:\n", 1)[0]
        self.assertIn(f"'{ROTATION_LOCK}' || '{PUBLICATION_LOCK}'", header)
        self.assertIn("inputs.operation == 'rotate'", header)
        self.assertIn("cancel-in-progress: false", header)
        self.assertNotIn("quick-skin-github-pages", header)
        self.assertNotIn("quick-skin-pages-evidence-rotation", header)

    def test_lost_wakes_are_recovered_by_the_hourly_schedule(self) -> None:
        header = caller_text().split("\njobs:\n", 1)[0]
        self.assertIn("  schedule:\n" + RECOVERY_CRON, header)
        self.assertEqual(1, header.count("cron:"))


if __name__ == "__main__":
    unittest.main()
