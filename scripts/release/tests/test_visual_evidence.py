from __future__ import annotations

import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402
from check_visual_review import (  # noqa: E402
    MAX_JSON_BYTES,
    ReviewError,
    load,
    render,
    validate,
    validate_input,
    validate_manifest,
    write_normalized_report,
)
from visual_evidence import (  # noqa: E402
    MAX_RESULT_BYTES,
    MAX_RESULT_FILES,
    VisualEvidenceError,
    collect_evidence,
    load_catalog,
    validate_png_snapshot,
)
from visual_review import (  # noqa: E402
    build_manifest,
    curate_manifest,
    load_reference_frames,
    reference_identity,
    validate_expected_row,
)


PNG_WIDTH = 640
PNG_HEIGHT = 360
_png_buffer = io.BytesIO()
Image.new("RGB", (PNG_WIDTH, PNG_HEIGHT), (12, 34, 56)).save(
    _png_buffer,
    format="PNG",
    optimize=False,
    compress_level=9,
)
PNG = _png_buffer.getvalue()
PNG_PIXEL_SHA256 = hashlib.sha256(
    bytes((12, 34, 56)) * PNG_WIDTH * PNG_HEIGHT
).hexdigest()


class VisualEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.e2e_root = self.root / "e2e-out"
        self.catalog_path = self.root / "scenario-contract.json"
        self.contract_hash = ""

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_catalog(self, captures: list[tuple[str, str, str]]) -> None:
        scenario_order = list(dict.fromkeys(item[0] for item in captures))
        scenarios = []
        for scenario in scenario_order:
            role_order = list(
                dict.fromkeys(
                    role
                    for item_scenario, role, _step in captures
                    if item_scenario == scenario
                )
            )
            roles = []
            for role in role_order:
                steps = [
                    {
                        "id": step,
                        "assertion_required": True,
                        "capture": {
                            "title": f"{scenario} {step}",
                            "review_tier": "key",
                            "expectation": f"Expected {scenario} {step}",
                            "probes": [],
                        },
                    }
                    for item_scenario, item_role, step in captures
                    if item_scenario == scenario and item_role == role
                ]
                roles.append(
                    {"role": role, "steps": steps, "comparisons": []}
                )
            orchestration = {"mode": "single-client"}
            if role_order == ["client_a", "client_b"]:
                orchestration = {
                    "mode": "sequential-two-client",
                    "role_order": role_order,
                }
            scenarios.append(
                {
                    "scenario": scenario,
                    "execution_profiles": (
                        ["runtime-default", "pr", "release"]
                        if not scenarios
                        else ["pr", "release"]
                    ),
                    "orchestration": orchestration,
                    "roles": roles,
                }
            )
        self.catalog_path.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "gui_text_reference_size": [1600, 900],
                    "scenarios": scenarios,
                }
            )
            + "\n",
            encoding="utf-8",
        )
        self.contract_hash = load_catalog(self.catalog_path).contract_sha256

    def write_result(
        self,
        scenario: str,
        *,
        step: str = "baseline",
        filename: str = "same.png",
        digest: str | None = None,
        status: str = "pass",
    ) -> Path:
        artifact = "fabric-1.20.1"
        profile_relative = Path("profiles") / f"{artifact}--1.20.1--{scenario}"
        profile = self.e2e_root / profile_relative
        screenshot = profile / "client_a" / "screenshots" / filename
        screenshot.parent.mkdir(parents=True, exist_ok=True)
        screenshot.write_bytes(PNG)
        file_sha256 = hashlib.sha256(PNG).hexdigest() if digest is None else digest
        result = {
            "artifact_node": artifact,
            "runtime_version": "1.20.1",
            "loader": "fabric",
            "scenario": scenario,
            "contract_sha256": self.contract_hash,
            "jar_sha256": "a" * 64,
            "installed_quickskin": [
                {"path": "server/mods/quick-skin.jar", "sha256": "a" * 64},
                {"path": "client_a/mods/quick-skin.jar", "sha256": "a" * 64},
            ],
            "port": 12345,
            "status": status,
            "profile": profile_relative.as_posix(),
            "elapsed_s": 1.0,
            "reports": {
                "client_a": {
                    "version": "1.20.1",
                    "role": "client_a",
                    "scenario": scenario,
                    "contract_sha256": self.contract_hash,
                    "status": "pass",
                    "steps": [
                        {
                            "name": step,
                            "status": "pass",
                            "message": "assertion passed",
                            "screenshot": filename,
                        }
                    ],
                    "pixel_validation": {
                        "screenshots": {
                            step: {
                                "width": PNG_WIDTH,
                                "height": PNG_HEIGHT,
                                "file_sha256": file_sha256,
                                "pixel_sha256": PNG_PIXEL_SHA256,
                                "luma_entropy": 1.0,
                                "meaningful_colors": 1,
                                "dark_fraction": 0.0,
                                "light_fraction": 0.0,
                            }
                        },
                        "comparisons": {},
                    },
                }
            },
        }
        result_path = profile / "result.json"
        result_path.write_text(json.dumps(result), encoding="utf-8")
        return result_path

    def write_compact_reference(
        self,
        capture_id: str,
        *,
        dimensions: tuple[int, int] = (800, 450),
        color: tuple[int, int, int] = (80, 60, 40),
    ) -> Path:
        catalog = load_catalog(self.catalog_path)
        capture = catalog.by_id[capture_id]
        branch = "forge-and-fabric-1.20.1"
        artifact_node = "fabric-1.20.1"
        bundle = self.root / "reference-evidence" / branch
        images = bundle / "images"
        images.mkdir(parents=True)
        encoded = io.BytesIO()
        Image.new("RGB", dimensions, color).save(
            encoded,
            format="WEBP",
            quality=82,
            method=6,
            exact=True,
        )
        payload = encoded.getvalue()
        file_sha256 = hashlib.sha256(payload).hexdigest()
        asset = images / f"{file_sha256}.webp"
        asset.write_bytes(payload)
        with Image.open(io.BytesIO(payload)) as image:
            rendered = image.convert("RGB")
            pixel_sha256 = hashlib.sha256(rendered.tobytes()).hexdigest()
        frame = {
            "artifact_node": artifact_node,
            "version": "1.20.1",
            "loader": "fabric",
            "scenario": capture["scenario"],
            "role": capture["role"],
            "step": capture["step"],
            "frame_id": (
                f"{artifact_node}/{capture['scenario']}/{capture['role']}/"
                f"{capture['step']}"
            ),
            "capture_id": capture_id,
            "title": capture["title"],
            "expectation": capture["expectation"],
            "review_tier": capture["review_tier"],
            "derivative": {
                "asset": f"images/{file_sha256}.webp",
                "format": "webp",
                "file_sha256": file_sha256,
                "width": dimensions[0],
                "height": dimensions[1],
                "pixel_validation": {
                    "file_sha256": file_sha256,
                    "pixel_sha256": pixel_sha256,
                    "width": dimensions[0],
                    "height": dimensions[1],
                },
            },
        }
        manifest = {
            "schema_version": 2,
            "contract_sha256": self.contract_hash,
            "release": {
                "branch": branch,
                "artifacts": [
                    {
                        "artifact_node": artifact_node,
                        "version": "1.20.1",
                        "loader": "fabric",
                    }
                ],
            },
            "frames": [frame],
        }
        (bundle / "manifest.json").write_text(
            json.dumps(manifest) + "\n", encoding="utf-8"
        )
        return self.root / "reference-evidence"

    def test_catalog_exactly_covers_runtime_screenshot_contract(self) -> None:
        catalog = load_catalog()
        runtime = {
            (scenario.scenario, role.role, step.id)
            for scenario in packaged_runtime.SCENARIO_CONTRACT.scenarios
            for role in scenario.roles
            for step in role.steps
            if step.capture is not None
        }
        self.assertEqual(runtime, set(catalog.by_key))

    def test_skin_menu_background_remains_in_the_key_visual_review(self) -> None:
        capture = load_catalog().by_id["full.client_a.skin_menu_screen"]

        self.assertEqual("key", capture["review_tier"])
        self.assertIn("dark, subtly starred background", capture["expectation"])
        self.assertIn("radial wash", capture["expectation"])

    def test_semantic_identity_keeps_same_filename_in_two_scenarios(self) -> None:
        self.write_catalog(
            [
                ("phase0-smoke", "client_a", "baseline"),
                ("propagation", "client_a", "baseline"),
            ]
        )
        self.write_result("phase0-smoke")
        self.write_result("propagation")

        lanes, frames, comparisons = collect_evidence(
            self.e2e_root, load_catalog(self.catalog_path)
        )

        self.assertEqual(2, len(lanes))
        self.assertEqual(2, len(frames))
        self.assertEqual(2, len({frame["frame_id"] for frame in frames}))
        self.assertEqual(
            {
                "phase0-smoke.client_a.baseline",
                "propagation.client_a.baseline",
            },
            {frame["capture_id"] for frame in frames},
        )
        self.assertEqual([], comparisons)

    def test_rejects_digest_drift_non_pass_and_path_traversal(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        cases = (
            {"digest": "0" * 64},
            {"status": "fail"},
            {"filename": "../same.png"},
        )
        for index, values in enumerate(cases):
            with self.subTest(values=values):
                case_root = self.root / f"case-{index}"
                original = self.e2e_root
                self.e2e_root = case_root
                try:
                    if values.get("filename") == "../same.png":
                        result_path = self.write_result("phase0-smoke")
                        data = json.loads(result_path.read_text(encoding="utf-8"))
                        data["reports"]["client_a"]["steps"][0]["screenshot"] = "../same.png"
                        result_path.write_text(json.dumps(data), encoding="utf-8")
                    else:
                        self.write_result("phase0-smoke", **values)
                    with self.assertRaises(VisualEvidenceError):
                        collect_evidence(self.e2e_root, load_catalog(self.catalog_path))
                finally:
                    self.e2e_root = original

    def test_rejects_a_truncated_png_even_when_header_and_file_hash_match(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        result = json.loads(result_path.read_text(encoding="utf-8"))
        screenshot = result_path.parent / "client_a" / "screenshots" / "same.png"
        truncated = screenshot.read_bytes()[:33]
        screenshot.write_bytes(truncated)
        result["reports"]["client_a"]["pixel_validation"]["screenshots"][
            "baseline"
        ]["file_sha256"] = hashlib.sha256(truncated).hexdigest()
        result_path.write_text(json.dumps(result), encoding="utf-8")

        with self.assertRaisesRegex(VisualEvidenceError, "fully decode"):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))

    def test_rejects_animated_png_evidence(self) -> None:
        animated_buffer = io.BytesIO()
        Image.new("RGB", (PNG_WIDTH, PNG_HEIGHT), (12, 34, 56)).save(
            animated_buffer,
            format="PNG",
            save_all=True,
            append_images=[
                Image.new("RGB", (PNG_WIDTH, PNG_HEIGHT), (56, 34, 12))
            ],
            duration=100,
            loop=0,
        )
        animated = self.root / "animated.png"
        animated.write_bytes(animated_buffer.getvalue())

        with self.assertRaisesRegex(VisualEvidenceError, "static PNG"):
            validate_png_snapshot(animated)

    def test_ai_manifest_is_non_empty_and_uses_unique_frame_labels(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        self.write_result("phase0-smoke")

        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos={("1.20.1", "fabric")},
        )

        self.assertEqual(1, len(manifest))
        self.assertEqual("fabric-1.20.1/phase0-smoke/client_a/baseline", manifest[0]["label"])

    def test_curated_ai_manifest_contains_only_content_addressed_frames(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        self.write_result("phase0-smoke")
        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos=None,
        )
        output = self.root / "review-input"

        curated = curate_manifest(manifest, output)

        expected_path = curated[0]["path"]
        self.assertEqual(expected_path, curated[0]["path"])
        served = self.root / expected_path
        self.assertEqual(expected_path.split("/")[-1][:-4], hashlib.sha256(served.read_bytes()).hexdigest())
        self.assertEqual(PNG_PIXEL_SHA256, validate_png_snapshot(served)[2])
        self.assertEqual(
            curated,
            json.loads(
                (output / "visual-review-manifest.json").read_text(encoding="utf-8")
            ),
        )
        self.assertEqual([], list(self.root.glob(".review-input.curating-*")))
        self.assertEqual(
            1,
            validate_input(
                json.loads(
                    (output / "visual-review-manifest.json").read_text(encoding="utf-8")
                ),
                output,
            ),
        )
        with self.assertRaisesRegex(VisualEvidenceError, "must not already exist"):
            curate_manifest(manifest, output)

    def test_paired_manifest_matches_every_capture_to_the_1_20_1_anchor(self) -> None:
        capture_id = "phase0-smoke.client_a.baseline"
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        self.write_result("phase0-smoke")
        reference_root = self.write_compact_reference(capture_id)
        references = load_reference_frames(
            reference_root,
            self.catalog_path,
            branch="forge-and-fabric-1.20.1",
            artifact_node="fabric-1.20.1",
        )
        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=True,
            combos=None,
            reference_frames=references,
        )

        curated = curate_manifest(manifest, self.root / "review-input")

        self.assertEqual(1, len(curated))
        self.assertEqual(capture_id, curated[0]["capture_id"])
        self.assertEqual(
            "fabric-1.20.1/phase0-smoke/client_a/baseline",
            curated[0]["reference_label"],
        )
        self.assertNotEqual(curated[0]["path"], curated[0]["reference_path"])
        with Image.open(self.root / curated[0]["path"]) as candidate:
            self.assertEqual((800, 450), candidate.size)
        with Image.open(self.root / curated[0]["reference_path"]) as reference:
            self.assertEqual((800, 450), reference.size)
        self.assertEqual(1, validate_input(curated, self.root / "review-input"))
        self.assertEqual(
            2,
            len(list((self.root / "review-input" / "images").glob("*.png"))),
        )

    def test_paired_manifest_fails_closed_on_a_missing_or_changed_reference(self) -> None:
        capture_id = "phase0-smoke.client_a.baseline"
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        self.write_result("phase0-smoke")
        reference_root = self.write_compact_reference(capture_id)
        references = load_reference_frames(
            reference_root,
            self.catalog_path,
            branch="forge-and-fabric-1.20.1",
            artifact_node="fabric-1.20.1",
        )
        with self.assertRaisesRegex(VisualEvidenceError, "missing capture"):
            build_manifest(
                self.e2e_root,
                self.catalog_path,
                include_all=True,
                combos=None,
                reference_frames={},
            )

        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=True,
            combos=None,
            reference_frames=references,
        )
        Path(str(manifest[0]["reference_path"])).write_bytes(b"changed")
        with self.assertRaisesRegex(VisualEvidenceError, "visual reference"):
            curate_manifest(manifest, self.root / "changed-review-input")

    def test_visual_reference_identity_is_derived_from_protected_master(self) -> None:
        identity = reference_identity(ROOT / "release" / "release-matrix.json")

        self.assertEqual(
            {
                "release_branch": "forge-and-fabric-1.20.1",
                "artifact_node": "fabric-1.20.1",
                "version": "1.20.1",
                "loader": "fabric",
            },
            identity,
        )

        with mock.patch(
            "visual_review.load_matrix",
            return_value={
                "unit_test_version": "1.21.1",
                "project": {"release_branch": "fabric-and-neoforge-1.21.1"},
                "artifacts": [
                    {
                        "artifact_node": "fabric-1.21.1",
                        "artifact_version": "1.21.1",
                        "loader": "fabric",
                    }
                ],
            },
        ), self.assertRaisesRegex(VisualEvidenceError, "keep Minecraft 1.20.1"):
            reference_identity(self.root / "unused-matrix.json")

    def test_review_input_rejects_unreferenced_content(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        self.write_result("phase0-smoke")
        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos=None,
        )
        output = self.root / "review-input"
        curated = curate_manifest(manifest, output)
        (output / "images" / ("0" * 64 + ".png")).write_bytes(PNG)

        with self.assertRaisesRegex(ReviewError, "digest disagrees|inventory"):
            validate_input(curated, output)

    def test_curator_rejects_a_frame_swapped_after_evidence_validation(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos=None,
        )
        screenshot = result_path.parent / "client_a" / "screenshots" / "same.png"
        screenshot.write_bytes(PNG + b"changed-after-validation")

        with self.assertRaisesRegex(
            VisualEvidenceError, "changed after evidence validation"
        ):
            curate_manifest(manifest, self.root / "review-input")

    def test_curator_strips_png_payload_metadata_without_changing_pixels(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        screenshot = result_path.parent / "client_a" / "screenshots" / "same.png"
        marker = b"untrusted-trailing-review-instructions"
        screenshot.write_bytes(PNG + marker)
        result = json.loads(result_path.read_text(encoding="utf-8"))
        result["reports"]["client_a"]["pixel_validation"]["screenshots"][
            "baseline"
        ]["file_sha256"] = hashlib.sha256(PNG + marker).hexdigest()
        result_path.write_text(json.dumps(result), encoding="utf-8")
        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos=None,
        )

        curated = curate_manifest(manifest, self.root / "review-input")
        served = self.root / curated[0]["path"]

        self.assertNotIn(marker, served.read_bytes())
        self.assertEqual(PNG_PIXEL_SHA256, validate_png_snapshot(served)[2])

    def test_curator_enforces_minimum_dimensions_and_aggregate_pixels(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos=None,
        )
        repeated_manifest = [
            manifest[0],
            {**manifest[0], "label": "fabric-1.20.1/repeated/client_a/baseline"},
        ]
        with mock.patch(
            "visual_review.MAX_REVIEW_IMAGE_PIXELS",
            PNG_WIDTH * PNG_HEIGHT + 1,
        ):
            with self.assertRaisesRegex(VisualEvidenceError, "total pixel"):
                curate_manifest(repeated_manifest, self.root / "review-input")

        screenshot = result_path.parent / "client_a" / "screenshots" / "same.png"
        small_buffer = io.BytesIO()
        Image.new("RGB", (32, 32), (12, 34, 56)).save(small_buffer, format="PNG")
        small = small_buffer.getvalue()
        screenshot.write_bytes(small)
        result = json.loads(result_path.read_text(encoding="utf-8"))
        metrics = result["reports"]["client_a"]["pixel_validation"]["screenshots"][
            "baseline"
        ]
        metrics["width"] = 32
        metrics["height"] = 32
        metrics["file_sha256"] = hashlib.sha256(small).hexdigest()
        metrics["pixel_sha256"] = hashlib.sha256(bytes((12, 34, 56)) * 32 * 32).hexdigest()
        result_path.write_text(json.dumps(result), encoding="utf-8")
        small_manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos=None,
        )
        with self.assertRaisesRegex(VisualEvidenceError, "smaller than 640x360"):
            curate_manifest(small_manifest, self.root / "small-review-input")

    def test_expected_row_binds_identity_scenarios_and_one_production_jar(self) -> None:
        self.write_catalog(
            [
                ("phase0-smoke", "client_a", "baseline"),
                ("full", "client_a", "baseline"),
            ]
        )
        self.write_result("phase0-smoke")
        second_result_path = self.write_result("full")
        row = {
            "id": "fabric-1_20_1--1_20_1--pr-behavior",
            "artifact_node": "fabric-1.20.1",
            "runtime_version": "1.20.1",
            "loader": "fabric",
            "scenarios": "phase0-smoke,full",
        }

        validated = validate_expected_row(self.e2e_root, self.catalog_path, row)
        self.assertEqual(2, validated["lane_count"])
        self.assertEqual("a" * 64, validated["jar_sha256"])

        for field, value in (
            ("artifact_node", "forge-1.20.1"),
            ("runtime_version", "1.21.1"),
            ("loader", "forge"),
            ("scenarios", "phase0-smoke"),
        ):
            with self.subTest(field=field), self.assertRaises(VisualEvidenceError):
                validate_expected_row(
                    self.e2e_root,
                    self.catalog_path,
                    {**row, field: value},
                )

        second = json.loads(second_result_path.read_text(encoding="utf-8"))
        second["jar_sha256"] = "b" * 64
        for installed in second["installed_quickskin"]:
            installed["sha256"] = "b" * 64
        second_result_path.write_text(json.dumps(second), encoding="utf-8")
        with self.assertRaisesRegex(VisualEvidenceError, "multiple production JARs"):
            validate_expected_row(self.e2e_root, self.catalog_path, row)

    def test_rejects_a_missing_catalogued_client_role(self) -> None:
        self.write_catalog(
            [
                ("propagation", "client_a", "baseline"),
                ("propagation", "client_b", "baseline"),
            ]
        )
        self.write_result("propagation")

        with self.assertRaises(VisualEvidenceError):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))

    def test_rejects_untrusted_installed_quickskin_inventory(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        valid = json.loads(result_path.read_text(encoding="utf-8"))

        cases = []
        missing = json.loads(json.dumps(valid))
        del missing["installed_quickskin"]
        cases.append(("missing field", missing))
        missing_client = json.loads(json.dumps(valid))
        missing_client["installed_quickskin"].pop()
        cases.append(("missing client install", missing_client))
        wrong_digest = json.loads(json.dumps(valid))
        wrong_digest["installed_quickskin"][0]["sha256"] = "b" * 64
        cases.append(("wrong digest", wrong_digest))
        unsafe_path = json.loads(json.dumps(valid))
        unsafe_path["installed_quickskin"][0]["path"] = "../mods/quick-skin.jar"
        cases.append(("unsafe path", unsafe_path))
        duplicate_root = json.loads(json.dumps(valid))
        duplicate_root["installed_quickskin"][1]["path"] = (
            "server/mods/other-quick-skin.jar"
        )
        cases.append(("duplicate root", duplicate_root))
        unexpected_field = json.loads(json.dumps(valid))
        unexpected_field["installed_quickskin"][0]["size"] = 1
        cases.append(("unexpected entry field", unexpected_field))

        for label, mutation in cases:
            with self.subTest(label=label):
                result_path.write_text(json.dumps(mutation), encoding="utf-8")
                with self.assertRaises(VisualEvidenceError):
                    collect_evidence(
                        self.e2e_root,
                        load_catalog(self.catalog_path),
                    )

    def test_revalidates_contract_hash_non_capture_steps_and_comparisons(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        valid = json.loads(result_path.read_text(encoding="utf-8"))

        mutations = []
        root_hash = json.loads(json.dumps(valid))
        root_hash["contract_sha256"] = "0" * 64
        mutations.append(("result hash", root_hash))
        report_hash = json.loads(json.dumps(valid))
        report_hash["reports"]["client_a"]["contract_sha256"] = "0" * 64
        mutations.append(("report hash", report_hash))
        extra_comparison = json.loads(json.dumps(valid))
        extra_comparison["reports"]["client_a"]["pixel_validation"][
            "comparisons"
        ]["baseline->baseline"] = {
            "changed_fraction": 1.0,
            "rms_difference": 1.0,
            "required_changed_fraction": 0.1,
        }
        mutations.append(("extra comparison", extra_comparison))
        for label, mutation in mutations:
            with self.subTest(label=label):
                result_path.write_text(json.dumps(mutation), encoding="utf-8")
                with self.assertRaises(VisualEvidenceError):
                    collect_evidence(
                        self.e2e_root,
                        load_catalog(self.catalog_path),
                    )

        contract_payload = json.loads(
            self.catalog_path.read_text(encoding="utf-8")
        )
        contract_payload["scenarios"][0]["roles"][0]["steps"].append(
            {"id": "wait_only", "assertion_required": True}
        )
        self.catalog_path.write_text(
            json.dumps(contract_payload) + "\n",
            encoding="utf-8",
        )
        self.contract_hash = load_catalog(self.catalog_path).contract_sha256
        result_path = self.write_result("phase0-smoke")
        non_capture = json.loads(result_path.read_text(encoding="utf-8"))
        non_capture["reports"]["client_a"]["steps"].append(
            {
                "name": "wait_only",
                "status": "pass",
                "message": "wait completed",
                "screenshot": "unexpected.png",
            }
        )
        result_path.write_text(json.dumps(non_capture), encoding="utf-8")
        with self.assertRaises(VisualEvidenceError):
            collect_evidence(
                self.e2e_root,
                load_catalog(self.catalog_path),
            )

    def test_rejects_unknown_fields_at_every_packaged_schema_level(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        valid = json.loads(result_path.read_text(encoding="utf-8"))

        cases = []
        result_extra = json.loads(json.dumps(valid))
        result_extra["private"] = True
        cases.append(("result", result_extra))
        report_extra = json.loads(json.dumps(valid))
        report_extra["reports"]["client_a"]["private"] = True
        cases.append(("report", report_extra))
        step_extra = json.loads(json.dumps(valid))
        step_extra["reports"]["client_a"]["steps"][0]["private"] = True
        cases.append(("step", step_extra))
        pixel_extra = json.loads(json.dumps(valid))
        pixel_extra["reports"]["client_a"]["pixel_validation"]["private"] = True
        cases.append(("pixel validation", pixel_extra))
        metric_extra = json.loads(json.dumps(valid))
        metric_extra["reports"]["client_a"]["pixel_validation"]["screenshots"][
            "baseline"
        ]["private"] = True
        cases.append(("screenshot metric", metric_extra))

        for label, mutation in cases:
            with self.subTest(label=label):
                result_path.write_text(json.dumps(mutation), encoding="utf-8")
                with self.assertRaises(VisualEvidenceError):
                    collect_evidence(
                        self.e2e_root,
                        load_catalog(self.catalog_path),
                    )

    def test_rejects_duplicate_keys_nonfinite_elapsed_and_oversized_results(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        result_path = self.write_result("phase0-smoke")
        valid_text = result_path.read_text(encoding="utf-8")

        duplicate = valid_text.rstrip()[:-1] + ', "status": "pass"}'
        result_path.write_text(duplicate, encoding="utf-8")
        with self.assertRaisesRegex(VisualEvidenceError, "duplicate JSON object key"):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))

        result_path = self.write_result("phase0-smoke")
        nonfinite = json.loads(result_path.read_text(encoding="utf-8"))
        nonfinite["elapsed_s"] = float("nan")
        result_path.write_text(json.dumps(nonfinite), encoding="utf-8")
        with self.assertRaisesRegex(VisualEvidenceError, "non-finite JSON number"):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))

        result_path = self.write_result("phase0-smoke")
        overflow = result_path.read_text(encoding="utf-8").replace(
            '"elapsed_s": 1.0', '"elapsed_s": 1e999'
        )
        result_path.write_text(overflow, encoding="utf-8")
        with self.assertRaisesRegex(VisualEvidenceError, "non-finite JSON number"):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))

        result_path.write_bytes(b" " * (MAX_RESULT_BYTES + 1))
        with self.assertRaisesRegex(VisualEvidenceError, "between 1 and"):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))

    def test_rejects_more_than_the_bounded_result_file_inventory(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        profiles = self.e2e_root / "profiles"
        for index in range(MAX_RESULT_FILES + 1):
            profile = profiles / f"profile-{index:04d}"
            profile.mkdir(parents=True)
            (profile / "result.json").write_text("{}", encoding="utf-8")

        with self.assertRaisesRegex(VisualEvidenceError, "result files"):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))


class VisualReviewContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = [
            {
                "path": "/tmp/frame.png",
                "label": "lane/scenario/client/step",
                "capture_id": "scenario.client.step",
                "kind": "scenario.client.step",
                "expectation": "Expected player",
            }
        ]
        self.clean = [
            {
                "label": "lane/scenario/client/step",
                "matches": True,
                "visible": "Expected player",
                "anomalies": [],
                "defect": False,
            }
        ]

    def test_accepts_exact_typed_verdict_and_renders_advisory_status(self) -> None:
        verdicts = validate(self.manifest, self.clean)
        summary, has_defects = render(verdicts)
        self.assertFalse(has_defects)
        self.assertIn("advisory", summary.lower())

    def test_rejects_empty_duplicate_extra_and_incoherent_verdicts(self) -> None:
        cases = (
            ([], self.clean),
            (self.manifest, []),
            (self.manifest, self.clean + self.clean),
            (self.manifest, [{**self.clean[0], "extra": True}]),
            (self.manifest, [{**self.clean[0], "matches": False, "defect": False}]),
            (self.manifest, [{**self.clean[0], "label": "unexpected"}]),
        )
        for manifest, report in cases:
            with self.subTest(manifest=manifest, report=report), self.assertRaises(ReviewError):
                validate(manifest, report)

    def test_manifest_cannot_mix_paired_and_unpaired_entries(self) -> None:
        paired = {
            **self.manifest[0],
            "label": "other/scenario/client/step",
            "reference_path": "/tmp/reference.png",
            "reference_label": "reference/scenario/client/step",
        }

        with self.assertRaisesRegex(ReviewError, "cannot mix"):
            validate_manifest([self.manifest[0], paired])
        with self.assertRaisesRegex(ReviewError, "must pair every candidate"):
            validate_manifest(self.manifest, require_paired=True)
        self.assertEqual(
            [paired], validate_manifest([paired], require_paired=True)[0]
        )

    def test_rejects_control_characters_and_bounded_output_overflow(self) -> None:
        cases = (
            [{**self.clean[0], "visible": "bad\x1bterminal"}],
            [{**self.clean[0], "visible": "x" * 2049}],
            [{**self.clean[0], "anomalies": ["x"] * 17}],
            self.clean * 513,
        )
        for report in cases:
            with self.subTest(report_length=len(report)), self.assertRaises(ReviewError):
                validate(self.manifest, report)

    def test_json_loader_is_regular_bounded_and_rejects_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            duplicate = root / "duplicate.json"
            duplicate.write_text('{"value":1,"value":2}', encoding="utf-8")
            with self.assertRaisesRegex(ReviewError, "duplicate JSON key"):
                load(duplicate, "duplicate")

            oversized = root / "oversized.json"
            oversized.write_bytes(b" " * (MAX_JSON_BYTES + 1))
            with self.assertRaisesRegex(ReviewError, "between 1 and"):
                load(oversized, "oversized")

            linked = root / "linked.json"
            linked.symlink_to(duplicate)
            with self.assertRaises(ReviewError):
                load(linked, "linked")

    def test_writes_only_a_schema_normalized_bounded_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "visual-review-report.json"
            report = [{**self.clean[0], "visible": "  Expected player  "}]
            verdicts = validate(self.manifest, report)

            write_normalized_report(destination, verdicts)

            normalized = json.loads(destination.read_text(encoding="utf-8"))
            self.assertEqual("Expected player", normalized[0]["visible"])
            self.assertEqual(set(self.clean[0]), set(normalized[0]))


if __name__ == "__main__":
    unittest.main()
