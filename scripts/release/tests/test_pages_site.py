from __future__ import annotations

import hashlib
import json
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
sys.path.insert(0, str(ROOT / "scripts" / "pages"))
sys.path.insert(0, str(ROOT / "scripts" / "release"))

from build_site import SiteBuildError, build  # noqa: E402
from compatibility_evidence import (  # noqa: E402
    CompatibilityContractDriftError,
    CompatibilityEvidenceError,
    SCHEMA_VERSION as COMPATIBILITY_SCHEMA_VERSION,
    _expected_plan as expected_compatibility_plan,
    carry_forward as carry_compatibility_forward,
    main as compatibility_evidence_main,
    validate_bundle as validate_compatibility_bundle,
)
import evidence as evidence_module  # noqa: E402
from evidence import (  # noqa: E402
    MAX_MANIFEST_BYTES,
    PublicEvidenceError,
    compact_bundle,
    load_matrix_inventory,
    prepare,
    validate_bundle,
)
import packaged_runtime  # noqa: E402
from mod_compatibility import load_contract as load_compatibility_contract  # noqa: E402
from scenario_contract import load_contract as load_scenario_contract  # noqa: E402
from version_branches import parse_version_branch  # noqa: E402
from visual_evidence import load_catalog  # noqa: E402


def fixture_png(variant: int) -> bytes:
    base_width, base_height = 640, 360
    width, height = 1920, 1080
    rows: list[bytes] = []
    for y in range(base_height):
        row = bytearray()
        for x in range(base_width):
            if variant == 0:
                pixel = (
                    (x // 40 * 17) % 256,
                    (y // 30 * 23) % 256,
                    ((x // 40 + y // 30) * 31) % 256,
                )
            else:
                pixel = (
                    (x // 40 * 17 + 83) % 256,
                    (y // 30 * 23 + 47) % 256,
                    ((x // 40 + y // 30) * 31 + 131) % 256,
                )
            row.extend(pixel * 3)
        encoded_row = b"\0" + bytes(row)
        rows.extend((encoded_row, encoded_row, encoded_row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        checksum = zlib.crc32(kind + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
        + chunk(b"IEND", b"")
    )


PNGS = (fixture_png(0), fixture_png(1))
PIXEL_METRICS = (
    {
        "width": 1920,
        "height": 1080,
        "file_sha256": hashlib.sha256(PNGS[0]).hexdigest(),
        "pixel_sha256": "06300562182f93c7abf129a15ccfa1906edab2776ae28b990979da5a5da1f9c3",
        "luma_entropy": 6.991,
        "meaningful_colors": 32,
        "dark_fraction": 0.0049,
        "light_fraction": 0.0,
    },
    {
        "width": 1920,
        "height": 1080,
        "file_sha256": hashlib.sha256(PNGS[1]).hexdigest(),
        "pixel_sha256": "c05b5932d66ce3873d4de0b609a495c7dd3a4b18c54c04068b6018f56e1ee6c4",
        "luma_entropy": 7.067,
        "meaningful_colors": 32,
        "dark_fraction": 0.0,
        "light_fraction": 0.0033,
    },
)


class PagesSiteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.evidence_root = self.root / "evidence"
        self.catalog = load_catalog()
        self.next_run_id = 1000

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_valid_matrix(self, branch: str, version: str) -> Path:
        parsed = parse_version_branch(branch)
        assert parsed is not None
        java = 17 if version == "1.20.1" else 21
        architectury_version = "9.2.14" if version == "1.20.1" else "13.0.8"
        fabric_api_version = (
            "0.92.6+1.20.1" if version == "1.20.1" else f"0.116.5+{version}"
        )
        loader_versions = {
            "fabric": "0.17.3",
            "forge": f"{version}-47.4.9",
            "neoforge": "21.1.200",
        }
        version_parts = [int(part) for part in version.split(".")]
        version_parts[-1] += 1
        upper_version = ".".join(str(part) for part in version_parts)

        artifacts: list[dict[str, object]] = []
        runtimes: list[dict[str, object]] = []
        installers: dict[str, dict[str, str]] = {}
        properties = {
            "mod_version": "1.0.0-test",
            f"minecraft_version_{version.replace('.', '_')}": version,
            f"java_version_{version.replace('.', '_')}": str(java),
            f"architectury_api_version_{version.replace('.', '_')}": (
                architectury_version
            ),
        }
        for loader in parsed.loaders:
            node = f"{loader}-{version}"
            loader_version = loader_versions[loader]
            installer = (
                "fabric-1.1.0"
                if loader == "fabric"
                else f"{loader}-{loader_version}"
            )
            installers[installer] = {
                "url": f"https://example.invalid/{installer}.jar",
                "sha256": hashlib.sha256(installer.encode()).hexdigest(),
            }
            metadata: dict[str, object] = {
                "file": (
                    "fabric.mod.json"
                    if loader == "fabric"
                    else (
                        "META-INF/mods.toml"
                        if loader == "forge"
                        else "META-INF/neoforge.mods.toml"
                    )
                ),
                "loader": ">=0.17.0" if loader == "fabric" else "[1,)",
                "architectury": (
                    f">={architectury_version}"
                    if loader == "fabric"
                    else f"[{architectury_version},)"
                ),
            }
            if loader != "fabric":
                metadata.update(
                    {
                        "loader_api": "[1,)",
                        "pack_format": 15,
                        "server_data_pack_format": 15,
                    }
                )
            artifacts.append(
                {
                    "artifact_node": node,
                    "artifact_version": version,
                    "loader": loader,
                    "java": java,
                    "no_remap": False,
                    "metadata_range": (
                        f"={version}"
                        if loader == "fabric"
                        else f"[{version},{upper_version})"
                    ),
                    "gradle_task": f":{loader}:{version}:remapJar",
                    "harness_task": f":{loader}:{version}:remapE2EHarnessJar",
                    "jar": (
                        f"{loader}/versions/{version}/build/libs/"
                        f"Quick Skin - {loader.title()} - {version}-{{mod_version}}.jar"
                    ),
                    "harness_jar": (
                        f"{loader}/versions/{version}/build/libs/"
                        f"Quick Skin E2E - {loader.title()} - {version}-0.0.0.jar"
                    ),
                    "game_versions": [version],
                    "metadata": metadata,
                }
            )
            runtime: dict[str, object] = {
                "artifact_node": node,
                "runtime_version": version,
                "loader": loader,
                "jar_sha256": "from:artifact-manifest",
                "port": 0,
                "java": java,
                "loader_version": loader_version,
                "installer": installer,
                "architectury": {
                    "kind": "maven",
                    "version": architectury_version,
                },
                "scheduled_anchor": True,
                "pr_anchor": True,
            }
            suffix = version.replace(".", "_")
            if loader == "fabric":
                runtime["fabric_api"] = fabric_api_version
                properties[f"fabric_loader_version_{suffix}"] = loader_version
                properties[f"fabric_api_version_{suffix}"] = fabric_api_version
            else:
                properties[f"{loader}_version_{suffix}"] = loader_version
            runtimes.append(runtime)

        repository = self.root / "matrix-repositories" / branch
        matrix = repository / "release" / "release-matrix.json"
        matrix.parent.mkdir(parents=True, exist_ok=True)
        source_modules = ("common", *parsed.loaders)
        for module in source_modules:
            (repository / module / "src" / "main" / "java").mkdir(
                parents=True, exist_ok=True
            )
        (repository / "gradle.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in sorted(properties.items())),
            encoding="utf-8",
        )
        matrix.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "lane_count": len(parsed.loaders),
                    "unit_test_version": version,
                    "project": {
                        "name": "Quick Skin",
                        "mod_id": "quickskin",
                        "description": "Change and synchronize Minecraft appearances.",
                        "mod_version_property": "mod_version",
                        "release_branch": branch,
                        "modrinth_id": "zAIE84Ch",
                        "curseforge_id": 1323980,
                        "homepage": "https://modrinth.com/mod/quick-skin",
                        "sources": "https://github.com/AkaNebur/Quick-Skin-Mod",
                        "issues": "https://github.com/AkaNebur/Quick-Skin-Mod/issues",
                        "license": "All Rights Reserved",
                    },
                    "source_overlays": {
                        module: {} for module in source_modules
                    },
                    "installers": installers,
                    "artifacts": artifacts,
                    "runtimes": runtimes,
                }
            ),
            encoding="utf-8",
        )
        return matrix

    def write_branch(self, branch: str, version: str) -> Path:
        self.next_run_id += 10
        parsed = parse_version_branch(branch)
        assert parsed is not None
        e2e_root = self.root / f"e2e-{version}"
        scenarios = list(self.catalog.contract.scenarios_for_profile("pr"))
        for loader in parsed.loaders:
            artifact_node = f"{loader}-{version}"
            jar_sha256 = hashlib.sha256(f"jar:{artifact_node}".encode()).hexdigest()
            for scenario in scenarios:
                profile_relative = (
                    Path("profiles") / f"{artifact_node}--{version}--{scenario}"
                )
                profile = e2e_root / profile_relative
                reports: dict[str, object] = {}
                roles = list(self.catalog.contract.expected_roles(scenario))
                for role in roles:
                    role_contract = self.catalog.contract.role(scenario, role)
                    pairs = role_contract.comparisons
                    second_steps = {
                        comparison.second_step for comparison in pairs
                    }
                    steps = []
                    metrics = {}
                    screenshots = profile / role / "screenshots"
                    screenshots.mkdir(parents=True, exist_ok=True)
                    for step_contract in role_contract.steps:
                        screenshot = None
                        if step_contract.capture is not None:
                            screenshot = f"{step_contract.id}.png"
                            variant = (
                                1 if step_contract.id in second_steps else 0
                            )
                            (screenshots / screenshot).write_bytes(PNGS[variant])
                            metrics[step_contract.id] = dict(
                                PIXEL_METRICS[variant]
                            )
                        steps.append(
                            {
                                "name": step_contract.id,
                                "status": "pass",
                                "message": "assertion passed",
                                "screenshot": screenshot,
                            }
                        )
                    comparison_metrics: dict[str, dict[str, object]] = {}
                    for comparison_contract in pairs:
                        first_step = comparison_contract.first_step
                        second_step = comparison_contract.second_step
                        comparison = packaged_runtime.compare_screenshots(
                            screenshots / f"{first_step}.png",
                            screenshots / f"{second_step}.png",
                            comparison_contract.minimum_changed_fraction,
                            comparison_contract.region,
                        )
                        comparison_metrics[f"{first_step}->{second_step}"] = comparison
                    reports[role] = {
                        "version": version,
                        "role": role,
                        "scenario": scenario,
                        "contract_sha256": self.catalog.contract_sha256,
                        "status": "pass",
                        "steps": steps,
                        "pixel_validation": {
                            "screenshots": metrics,
                            "comparisons": comparison_metrics,
                        },
                    }
                result = {
                    "artifact_node": artifact_node,
                    "runtime_version": version,
                    "loader": loader,
                    "scenario": scenario,
                    "contract_sha256": self.catalog.contract_sha256,
                    "jar_sha256": jar_sha256,
                    "installed_quickskin": [
                        {
                            "path": f"{install_root}/mods/quick-skin.jar",
                            "sha256": jar_sha256,
                        }
                        for install_root in ["server", *roles]
                    ],
                    "port": 12345,
                    "status": "pass",
                    "profile": profile_relative.as_posix(),
                    "elapsed_s": 2.5,
                    "reports": reports,
                }
                (profile / "result.json").write_text(
                    json.dumps(result), encoding="utf-8"
                )

        matrix = self.write_valid_matrix(branch, version)
        prepare(
            e2e_root=e2e_root,
            matrix_path=matrix,
            catalog_path=ROOT / "e2e" / "scenario-contract.json",
            output_root=self.evidence_root,
            repository="AkaNebur/Quick-Skin-Mod",
            source_run_id=str(self.next_run_id),
            source_branch=branch,
            source_sha="1" * 40,
            source_created_at="2026-08-02T12:00:00Z",
            target_run_id=str(self.next_run_id + 1),
            target_branch=branch,
            target_sha="2" * 40,
            target_created_at="2026-08-02T13:00:00Z",
        )
        return matrix

    def write_compatibility_bundle(self, branch: str) -> Path:
        from PIL import Image

        parsed = parse_version_branch(branch)
        assert parsed is not None
        compatibility_contract = load_compatibility_contract()
        scenario_contract = load_scenario_contract()
        runnable, not_applicable = expected_compatibility_plan(
            branch, compatibility_contract
        )
        compatibility_root = self.root / "compatibility"
        bundle = compatibility_root / branch
        images = bundle / "images"
        images.mkdir(parents=True)
        source_png = self.root / "compatibility-source.png"
        source_png.write_bytes(PNGS[0])
        rendering = self.root / "compatibility-rendering.webp"
        with Image.open(source_png) as image:
            image.convert("RGB").resize((1280, 720), Image.Resampling.LANCZOS).save(
                rendering, "WEBP", quality=80, method=6, exact=True
            )
        derivative_metrics = packaged_runtime.inspect_screenshot(
            rendering, expected_format="WEBP"
        )
        derivative_digest = derivative_metrics["file_sha256"]
        asset = images / f"{derivative_digest}.webp"
        asset.write_bytes(rendering.read_bytes())
        image_record = {
            "source": {
                "file_sha256": PIXEL_METRICS[0]["file_sha256"],
                "width": 1920,
                "height": 1080,
                "pixel_validation": PIXEL_METRICS[0],
            },
            "derivative": {
                "asset": f"images/{derivative_digest}.webp",
                "format": "webp",
                "file_sha256": derivative_digest,
                "width": 1280,
                "height": 720,
                "pixel_validation": derivative_metrics,
            },
        }
        captures = [
            capture
            for capture in scenario_contract.captures
            if capture.scenario == "mod-compatibility"
        ]
        lanes = []
        for lane_id, lane in sorted(runnable.items()):
            frames = []
            for capture in captures:
                frames.append(
                    {
                        "capture_id": capture.capture_id,
                        "reference_capture_id": capture.compatibility_reference_capture_id,
                        "title": capture.title,
                        "expectation": f"{capture.expectation} with {lane.mod.name}",
                        "runtime_evidence": "compatibility assertion passed",
                        "review_regions": [[0.0, 0.0, 1.0, 1.0]],
                        "candidate_semantic_sha256": "a" * 64,
                        "reference_semantic_sha256": "b" * 64,
                        "semantic_changed_fraction": 0.01,
                        "perceptual_delta": 0.02,
                        "semantic_valid": True,
                        "matches_reference": True,
                        "defect": False,
                        "candidate": image_record,
                        "reference": image_record,
                    }
                )
            lanes.append(
                {
                    "lane_id": lane_id,
                    "artifact_node": lane.artifact_node,
                    "version": lane.runtime_version,
                    "loader": lane.loader,
                    "mod": lane.mod.id,
                    "mod_name": lane.mod.name,
                    "mod_version": lane.artifact.version_number,
                    "mod_version_id": lane.artifact.version_id,
                    "review_run_id": 3000 + len(lanes),
                    "reviewed_frame_count": len(captures),
                    "review_manifest_sha256": "c" * 64,
                    "curation_proof_sha256": "d" * 64,
                    "review_report_sha256": "e" * 64,
                    "frames": frames,
                }
            )
        manifest = {
            "schema_version": COMPATIBILITY_SCHEMA_VERSION,
            "kind": "quick-skin-public-mod-compatibility",
            "repository": "AkaNebur/Quick-Skin-Mod",
            "contracts": {
                "scenario_sha256": scenario_contract.sha256,
                "compatibility_sha256": compatibility_contract.sha256,
            },
            "release": {
                "branch": branch,
                "version": parsed.version,
                "loaders": list(parsed.loaders),
            },
            "provenance": {
                "implementation_sha": "1" * 40,
                "base_run_id": 100,
                "source_sha": "2" * 40,
                "target_sha": "3" * 40,
                "compatibility_run_id": 200,
                "publication_run_id": 300,
                "coverage_sha": "2" * 40,
            },
            "lanes": lanes,
            "not_applicable": sorted(
                not_applicable.values(),
                key=lambda item: (item["version"], item["loader"], item["mod"]),
            ),
        }
        (bundle / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        validate_compatibility_bundle(
            compatibility_root,
            branch,
            expected_repository="AkaNebur/Quick-Skin-Mod",
            only_branch=True,
        )
        return compatibility_root

    def test_prepare_deduplicates_images_and_keeps_all_validated_captures(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")

        manifest = validate_bundle(
            self.evidence_root,
            branch,
            expected_repository="AkaNebur/Quick-Skin-Mod",
            expected_target_sha="2" * 40,
        )

        self.assertEqual(86, len(manifest["frames"]))
        self.assertEqual(8, len(manifest["lanes"]))
        self.assertEqual(
            self.catalog.contract_sha256,
            manifest["contract_sha256"],
        )
        self.assertEqual(2, len(list((self.evidence_root / branch / "images").glob("*.png"))))
        self.assertNotIn("source_path", json.dumps(manifest))

    def test_prepare_uses_the_complete_canonical_release_matrix_validator(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix_path = self.write_valid_matrix(branch, "1.20.1")
        original = json.loads(matrix_path.read_text(encoding="utf-8"))

        cases: list[tuple[str, dict[str, object]]] = []
        missing_lane_count = json.loads(json.dumps(original))
        del missing_lane_count["lane_count"]
        cases.append(("missing lane_count", missing_lane_count))
        boolean_lane_count = json.loads(json.dumps(original))
        boolean_lane_count["lane_count"] = True
        cases.append(("boolean lane_count", boolean_lane_count))
        legacy_root_policy = json.loads(json.dumps(original))
        legacy_root_policy["pr_scenarios"] = ["full"]
        cases.append(("legacy root policy", legacy_root_policy))
        legacy_runtime_policy = json.loads(json.dumps(original))
        legacy_runtime_policy["runtimes"][0]["scenario"] = "full"
        cases.append(("legacy runtime policy", legacy_runtime_policy))
        malformed_runtime = json.loads(json.dumps(original))
        malformed_runtime["runtimes"][0]["port"] = 1
        cases.append(("malformed runtime", malformed_runtime))

        for label, mutation in cases:
            with self.subTest(label=label):
                matrix_path.write_text(json.dumps(mutation), encoding="utf-8")
                with self.assertRaises(PublicEvidenceError):
                    load_matrix_inventory(matrix_path, branch, self.catalog.contract)
        matrix_path.write_text(json.dumps(original), encoding="utf-8")

    def test_matrix_preflight_rejects_duplicate_keys_and_nonfinite_numbers(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix_path = self.write_valid_matrix(branch, "1.20.1")
        original = matrix_path.read_text(encoding="utf-8")

        duplicate = original.rstrip()[:-1] + ', "lane_count": 2}'
        matrix_path.write_text(duplicate, encoding="utf-8")
        with self.assertRaisesRegex(PublicEvidenceError, "duplicate JSON object key"):
            load_matrix_inventory(matrix_path, branch, self.catalog.contract)

        nonfinite = json.loads(original)
        nonfinite["runtimes"][0]["port"] = float("inf")
        matrix_path.write_text(json.dumps(nonfinite), encoding="utf-8")
        with self.assertRaisesRegex(PublicEvidenceError, "non-finite JSON number"):
            load_matrix_inventory(matrix_path, branch, self.catalog.contract)

    def test_manifest_rejects_duplicate_nonfinite_boolean_and_oversized_json(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        manifest_path = self.evidence_root / branch / "manifest.json"
        original_text = manifest_path.read_text(encoding="utf-8")
        original = json.loads(original_text)

        duplicate = original_text.rstrip()[:-1] + ', "schema_version": 1}'
        manifest_path.write_text(duplicate, encoding="utf-8")
        with self.assertRaisesRegex(PublicEvidenceError, "duplicate JSON object key"):
            validate_bundle(self.evidence_root, branch)

        nonfinite = json.loads(json.dumps(original))
        nonfinite["lanes"][0]["elapsed_s"] = float("nan")
        manifest_path.write_text(json.dumps(nonfinite), encoding="utf-8")
        with self.assertRaisesRegex(PublicEvidenceError, "non-finite JSON number"):
            validate_bundle(self.evidence_root, branch)

        overflow = original_text.replace('"elapsed_s": 2.5', '"elapsed_s": 1e999')
        manifest_path.write_text(overflow, encoding="utf-8")
        with self.assertRaisesRegex(PublicEvidenceError, "non-finite JSON number"):
            validate_bundle(self.evidence_root, branch)

        boolean_schema = json.loads(json.dumps(original))
        boolean_schema["schema_version"] = True
        manifest_path.write_text(json.dumps(boolean_schema), encoding="utf-8")
        with self.assertRaisesRegex(PublicEvidenceError, "schema_version"):
            validate_bundle(self.evidence_root, branch)

        manifest_path.write_bytes(b" " * (MAX_MANIFEST_BYTES + 1))
        with self.assertRaisesRegex(PublicEvidenceError, "size limit"):
            validate_bundle(self.evidence_root, branch)

    def test_manifest_rejects_unknown_fields_at_each_container_level(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        manifest_path = self.evidence_root / branch / "manifest.json"
        original = json.loads(manifest_path.read_text(encoding="utf-8"))

        mutations: list[tuple[str, dict[str, object]]] = []
        root_extra = json.loads(json.dumps(original))
        root_extra["private"] = True
        mutations.append(("manifest", root_extra))
        release_extra = json.loads(json.dumps(original))
        release_extra["release"]["private"] = True
        mutations.append(("release", release_extra))
        provenance_extra = json.loads(json.dumps(original))
        provenance_extra["provenance"]["private"] = True
        mutations.append(("provenance", provenance_extra))
        provenance_record_extra = json.loads(json.dumps(original))
        provenance_record_extra["provenance"]["source"]["private"] = True
        mutations.append(("provenance record", provenance_record_extra))
        artifact_extra = json.loads(json.dumps(original))
        artifact_extra["release"]["artifacts"][0]["private"] = True
        mutations.append(("artifact", artifact_extra))
        lane_extra = json.loads(json.dumps(original))
        lane_extra["lanes"][0]["private"] = True
        mutations.append(("lane", lane_extra))
        frame_extra = json.loads(json.dumps(original))
        frame_extra["frames"][0]["private"] = True
        mutations.append(("frame", frame_extra))
        comparison_extra = json.loads(json.dumps(original))
        comparison_extra["comparisons"][0]["private"] = True
        mutations.append(("comparison", comparison_extra))

        for label, mutation in mutations:
            with self.subTest(label=label):
                manifest_path.write_text(json.dumps(mutation), encoding="utf-8")
                with self.assertRaises(PublicEvidenceError):
                    validate_bundle(self.evidence_root, branch)

    def test_public_image_limits_match_export_and_stop_before_expensive_validation(self) -> None:
        self.assertEqual(
            packaged_runtime.MAX_EVIDENCE_SCREENSHOT_BYTES,
            evidence_module.MAX_IMAGE_BYTES,
        )
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        bundle = self.evidence_root / branch
        manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
        assets = sorted({frame["asset"] for frame in manifest["frames"]})
        self.assertEqual(2, len(assets))
        total_size = sum((bundle / asset).stat().st_size for asset in assets)
        real_sha256_file = evidence_module.sha256_file
        real_inspect_screenshot = evidence_module.inspect_screenshot

        with (
            patch.object(evidence_module, "MAX_TOTAL_IMAGE_BYTES", total_size - 1),
            patch.object(
                evidence_module,
                "sha256_file",
                wraps=real_sha256_file,
            ) as hash_image,
            patch.object(
                evidence_module,
                "inspect_screenshot",
                wraps=real_inspect_screenshot,
            ) as decode_image,
        ):
            with self.assertRaisesRegex(PublicEvidenceError, "total image byte limit"):
                validate_bundle(self.evidence_root, branch)
        self.assertEqual(1, hash_image.call_count)
        self.assertEqual(1, decode_image.call_count)

    def test_image_directory_and_bundle_walk_have_explicit_entry_limits(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")

        with patch.object(evidence_module, "MAX_IMAGE_ENTRIES", 1):
            with self.assertRaisesRegex(PublicEvidenceError, "image directory.*entry limit"):
                validate_bundle(self.evidence_root, branch)

        with patch.object(evidence_module, "MAX_BUNDLE_ENTRIES", 3):
            with self.assertRaisesRegex(PublicEvidenceError, "bundle.*entry limit"):
                validate_bundle(self.evidence_root, branch)

    def test_compaction_snapshots_the_exact_validated_source_before_encoding(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact-toctou"
        snapshot = evidence_module._snapshot_verified_image
        mutated = False

        def mutate_before_snapshot(
            source: Path,
            destination: Path,
            **kwargs: object,
        ) -> None:
            nonlocal mutated
            if not mutated:
                source.write_bytes(source.read_bytes() + b"post-validation mutation")
                mutated = True
            snapshot(source, destination, **kwargs)

        with patch.object(
            evidence_module,
            "_snapshot_verified_image",
            side_effect=mutate_before_snapshot,
        ):
            with self.assertRaisesRegex(PublicEvidenceError, "digest changed after validation"):
                compact_bundle(self.evidence_root, compact_root, branch)

        self.assertTrue(mutated)
        self.assertFalse((compact_root / branch).exists())

    def test_compacts_validated_pngs_and_preserves_both_image_identities(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        raw_manifest = validate_bundle(self.evidence_root, branch)
        compact_root = self.root / "compact"

        compact_bundle(
            self.evidence_root,
            compact_root,
            branch,
            expected_repository="AkaNebur/Quick-Skin-Mod",
            expected_target_sha="2" * 40,
        )
        compact_manifest = validate_bundle(
            compact_root,
            branch,
            expected_kind="compact",
            expected_repository="AkaNebur/Quick-Skin-Mod",
            expected_target_sha="2" * 40,
        )

        self.assertEqual(2, compact_manifest["schema_version"])
        self.assertFalse(list((compact_root / branch).rglob("*.png")))
        self.assertEqual(
            2, len(list((compact_root / branch / "images").glob("*.webp")))
        )
        raw_by_id = {frame["frame_id"]: frame for frame in raw_manifest["frames"]}
        for frame in compact_manifest["frames"]:
            raw = raw_by_id[frame["frame_id"]]
            self.assertEqual(raw["file_sha256"], frame["file_sha256"])
            self.assertEqual((raw["width"], raw["height"]), (frame["width"], frame["height"]))
            derivative = frame["derivative"]
            published = compact_root / branch / derivative["asset"]
            self.assertEqual("webp", derivative["format"])
            self.assertEqual(
                derivative["file_sha256"], hashlib.sha256(published.read_bytes()).hexdigest()
            )
            self.assertEqual(
                derivative["file_sha256"], derivative["pixel_validation"]["file_sha256"]
            )
        self.assertTrue(
            all(
                "derivative_pixel_validation" in comparison
                for comparison in compact_manifest["comparisons"]
            )
        )

    def test_compaction_is_atomic_and_compact_validation_rejects_tampering(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        raw_manifest_path = self.evidence_root / branch / "manifest.json"
        original_manifest = json.loads(raw_manifest_path.read_text(encoding="utf-8"))
        original_manifest["frames"][0]["width"] += 1
        raw_manifest_path.write_text(json.dumps(original_manifest), encoding="utf-8")
        failed_output = self.root / "failed-compact"
        with self.assertRaises(PublicEvidenceError):
            compact_bundle(self.evidence_root, failed_output, branch)
        self.assertFalse((failed_output / branch).exists())

        self.evidence_root = self.root / "fresh-evidence"
        self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        manifest_path = compact_root / branch / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        derivative_path = compact_root / branch / manifest["frames"][0]["derivative"]["asset"]
        derivative_path.write_bytes(derivative_path.read_bytes() + b"tampered")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(compact_root, branch, expected_kind="compact")

    def test_compacting_a_compact_cache_preserves_exact_bytes(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        first = self.root / "compact-first"
        second = self.root / "compact-second"
        compact_bundle(self.evidence_root, first, branch)
        compact_bundle(first, second, branch)

        first_files = {
            path.relative_to(first / branch): path.read_bytes()
            for path in (first / branch).rglob("*")
            if path.is_file()
        }
        second_files = {
            path.relative_to(second / branch): path.read_bytes()
            for path in (second / branch).rglob("*")
            if path.is_file()
        }
        self.assertEqual(first_files, second_files)

    def test_raw_handoff_contract_rejects_a_precompacted_bundle(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        rejected_output = self.root / "rejected-handoff"
        compact_bundle(self.evidence_root, compact_root, branch)

        with self.assertRaises(PublicEvidenceError):
            compact_bundle(
                compact_root,
                rejected_output,
                branch,
                expected_input_kind="raw",
            )

        self.assertFalse((rejected_output / branch).exists())

    def test_compact_manifest_rejects_source_and_derivative_metadata_drift(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        manifest_path = compact_root / branch / "manifest.json"
        original = json.loads(manifest_path.read_text(encoding="utf-8"))

        cases: list[tuple[str, dict[str, object]]] = []
        source_hash = json.loads(json.dumps(original))
        source_hash["frames"][0]["file_sha256"] = "f" * 64
        cases.append(("source hash", source_hash))
        source_dimensions = json.loads(json.dumps(original))
        source_dimensions["frames"][0]["width"] += 1
        cases.append(("source dimensions", source_dimensions))
        derivative_hash = json.loads(json.dumps(original))
        derivative_hash["frames"][0]["derivative"]["file_sha256"] = "f" * 64
        cases.append(("derivative hash", derivative_hash))
        derivative_dimensions = json.loads(json.dumps(original))
        derivative_dimensions["frames"][0]["derivative"]["width"] -= 1
        cases.append(("derivative dimensions", derivative_dimensions))
        derivative_metrics = json.loads(json.dumps(original))
        derivative_metrics["frames"][0]["derivative"]["pixel_validation"][
            "private_note"
        ] = "forbidden"
        cases.append(("derivative payload", derivative_metrics))
        derivative_comparison = json.loads(json.dumps(original))
        derivative_comparison["comparisons"][0]["derivative_pixel_validation"][
            "required_changed_fraction"
        ] = 0.0
        cases.append(("derivative comparison threshold", derivative_comparison))

        for label, manifest in cases:
            with self.subTest(label=label):
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaises(PublicEvidenceError):
                    validate_bundle(compact_root, branch, expected_kind="compact")
        manifest_path.write_text(json.dumps(original), encoding="utf-8")

    def test_single_branch_artifact_rejects_a_sibling_bundle(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        self.write_branch("fabric-and-neoforge-1.21.1", "1.21.1")

        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch, only_branch=True)

    def test_build_rejects_a_branch_inventory_different_from_discovery(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")

        with self.assertRaises(SiteBuildError):
            build(
                evidence_root=self.evidence_root,
                output=self.root / "site-output",
                repository="AkaNebur/Quick-Skin-Mod",
                matrix_path=matrix,
                optimize=False,
                expected_branches={branch, "fabric-and-neoforge-1.21.1"},
            )

    def test_builds_multiversion_link_page_gallery_and_machine_inventory(self) -> None:
        first_matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        self.write_branch("fabric-and-neoforge-1.21.1", "1.21.1")
        output = self.root / "site-output"

        summary = build(
            evidence_root=self.evidence_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=first_matrix,
            optimize=False,
        )

        self.assertEqual(2, summary["versions"])
        self.assertEqual(172, summary["frames"])
        self.assertTrue((output / ".nojekyll").is_file())
        self.assertTrue((output / "index.html").is_file())
        self.assertTrue((output / "e2e" / "index.html").is_file())
        self.assertEqual(4, len(list((output / "e2e" / "images").glob("*/*.png"))))
        site_data = json.loads((output / "site-data.json").read_text(encoding="utf-8"))
        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        self.assertEqual(["1.21.1", "1.20.1"], [row["version"] for row in site_data["releases"]])
        self.assertEqual(172, len(gallery["frames"]))
        self.assertEqual(172, len({frame["frame_id"] for frame in gallery["frames"]}))
        sample = gallery["frames"][0]
        published = output / "e2e" / sample["image"]
        self.assertEqual(sample["published_file_sha256"], published.stem)
        self.assertEqual(sample["published_file_sha256"], hashlib.sha256(published.read_bytes()).hexdigest())
        self.assertEqual(sample["source_file_sha256"], sample["source_pixel_validation"]["file_sha256"])
        self.assertEqual("forge-and-fabric-1.20.1", next(
            frame["source_branch"] for frame in gallery["frames"] if frame["version"] == "1.20.1"
        ))
        self.assertEqual(sample["target_branch"], next(
            release["target_branch"] for release in gallery["releases"] if release["version"] == sample["version"]
        ))
        self.assertNotIn("file_sha256", sample)
        self.assertNotIn("branch", sample)
        fabric_ids = [
            frame["capture_id"]
            for frame in gallery["frames"]
            if frame["version"] == "1.20.1" and frame["loader"] == "fabric"
        ]
        self.assertLess(
            fabric_ids.index("full.client_a.animated_cape_apply"),
            fabric_ids.index("full.client_a.animated_cape_advance"),
        )
        self.assertLess(
            fabric_ids.index("propagation-live.client_b.observe_before"),
            fabric_ids.index("propagation-live.client_b.await_live_change"),
        )
        gallery_js = (output / "assets" / "gallery.js").read_text(encoding="utf-8")
        self.assertNotIn("innerHTML", gallery_js)
        for html in (output / "index.html", output / "e2e" / "index.html"):
            content = html.read_text(encoding="utf-8")
            self.assertIn("Content-Security-Policy", content)
            self.assertNotRegex(content, r'<script[^>]+src="https?://')
            self.assertNotRegex(content, r'<link[^>]+href="https?://[^\"]+\.css')

    def test_rejects_stale_sha_and_traversing_asset(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(
                self.evidence_root,
                branch,
                expected_target_sha="3" * 40,
            )

        manifest_path = self.evidence_root / branch / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["frames"][0]["asset"] = "../manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)

    def test_rejects_symlinked_public_image_even_when_bytes_match(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        manifest = json.loads(
            (self.evidence_root / branch / "manifest.json").read_text(encoding="utf-8")
        )
        image = self.evidence_root / branch / manifest["frames"][0]["asset"]
        outside = self.root / "outside.png"
        outside.write_bytes(PNGS[0])
        image.unlink()
        image.symlink_to(outside)

        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)

    def test_rejects_reduced_fabricated_or_payload_bearing_manifest(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        manifest_path = self.evidence_root / branch / "manifest.json"
        original = json.loads(manifest_path.read_text(encoding="utf-8"))

        cases: list[tuple[str, object]] = []
        contract_drift = json.loads(json.dumps(original))
        contract_drift["contract_sha256"] = "f" * 64
        cases.append(("scenario contract hash", contract_drift))
        reduced = json.loads(json.dumps(original))
        reduced["frames"].pop()
        cases.append(("missing frame", reduced))
        fabricated = json.loads(json.dumps(original))
        fabricated["frames"][0]["frame_id"] = (
            fabricated["frames"][0]["frame_id"].rsplit("/", 1)[0] + "/fabricated"
        )
        cases.append(("fabricated frame id", fabricated))
        frame_payload = json.loads(json.dumps(original))
        frame_payload["frames"][0]["pixel_validation"]["private_note"] = "secret"
        cases.append(("frame payload", frame_payload))
        blank_evidence = json.loads(json.dumps(original))
        blank_evidence["frames"][0]["runtime_evidence"] = "   "
        cases.append(("blank runtime evidence", blank_evidence))
        multiline_evidence = json.loads(json.dumps(original))
        multiline_evidence["frames"][0]["runtime_evidence"] = "assertion\npassed"
        cases.append(("multiline runtime evidence", multiline_evidence))
        unbounded_evidence = json.loads(json.dumps(original))
        unbounded_evidence["frames"][0]["runtime_evidence"] = "a" * 4097
        cases.append(("unbounded runtime evidence", unbounded_evidence))
        comparison_payload = json.loads(json.dumps(original))
        comparison_payload["comparisons"][0]["pixel_validation"]["private_note"] = "secret"
        cases.append(("comparison payload", comparison_payload))
        missing_comparison = json.loads(json.dumps(original))
        missing_comparison["comparisons"].pop()
        cases.append(("missing comparison", missing_comparison))
        weakened_comparison = json.loads(json.dumps(original))
        weakened_comparison["comparisons"][0]["pixel_validation"][
            "required_changed_fraction"
        ] = 0.0
        cases.append(("weakened comparison threshold", weakened_comparison))
        impossible_dimensions = json.loads(json.dumps(original))
        impossible_dimensions["frames"][0]["width"] = 1
        impossible_dimensions["frames"][0]["height"] = 1
        impossible_dimensions["frames"][0]["pixel_validation"]["width"] = 1
        impossible_dimensions["frames"][0]["pixel_validation"]["height"] = 1
        cases.append(("implausible dimensions", impossible_dimensions))
        jar_drift = json.loads(json.dumps(original))
        first_artifact = jar_drift["lanes"][0]["artifact_node"]
        drift_lane = next(
            lane
            for lane in jar_drift["lanes"][1:]
            if lane["artifact_node"] == first_artifact
        )
        drift_lane["jar_sha256"] = "f" * 64
        cases.append(("scenario jar drift", jar_drift))
        loader_drift = json.loads(json.dumps(original))
        loader_drift["release"]["artifacts"][1]["loader"] = loader_drift["release"][
            "artifacts"
        ][0]["loader"]
        cases.append(("duplicate loader", loader_drift))

        for label, data in cases:
            with self.subTest(label=label):
                manifest_path.write_text(json.dumps(data), encoding="utf-8")
                with self.assertRaises(PublicEvidenceError):
                    validate_bundle(self.evidence_root, branch)
        manifest_path.write_text(json.dumps(original), encoding="utf-8")

    def test_raw_and_compact_bundles_require_the_exact_ordered_public_scenarios(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact-scenario-policy"
        compact_bundle(self.evidence_root, compact_root, branch)

        for kind, root in (("raw", self.evidence_root), ("compact", compact_root)):
            manifest_path = root / branch / "manifest.json"
            original = json.loads(manifest_path.read_text(encoding="utf-8"))

            reordered = json.loads(json.dumps(original))
            reordered["release"]["scenarios"][0:2] = reversed(
                reordered["release"]["scenarios"][0:2]
            )

            reduced = json.loads(json.dumps(original))
            removed = reduced["release"]["scenarios"].pop()
            reduced["lanes"] = [
                lane for lane in reduced["lanes"] if lane["scenario"] != removed
            ]
            reduced["frames"] = [
                frame for frame in reduced["frames"] if frame["scenario"] != removed
            ]
            reduced["comparisons"] = [
                comparison
                for comparison in reduced["comparisons"]
                if comparison["scenario"] != removed
            ]

            for mutation, manifest in (("reordered", reordered), ("reduced", reduced)):
                with self.subTest(kind=kind, mutation=mutation):
                    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                    with self.assertRaisesRegex(
                        PublicEvidenceError, "exact protected public profile"
                    ):
                        validate_bundle(root, branch, expected_kind=kind)
            manifest_path.write_text(json.dumps(original), encoding="utf-8")

    def test_rejects_every_extra_entry_in_curated_bundle(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        bundle = self.evidence_root / branch
        extra = bundle / "secret.txt"
        extra.write_text("must not enter the public cache", encoding="utf-8")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)
        extra.unlink()

        nested = bundle / "images" / "nested"
        nested.mkdir()
        (nested / "secret.txt").write_text("also forbidden", encoding="utf-8")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)

    def test_optimized_asset_url_is_addressed_by_its_published_digest(self) -> None:
        matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        output = self.root / "optimized-site"
        build(
            evidence_root=self.evidence_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            optimize=True,
        )

        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        for frame in gallery["frames"]:
            published = output / "e2e" / frame["image"]
            digest = hashlib.sha256(published.read_bytes()).hexdigest()
            self.assertEqual("webp", frame["published_format"])
            self.assertEqual(digest, frame["published_file_sha256"])
            self.assertEqual(digest, published.stem)
            self.assertNotEqual(frame["source_file_sha256"], digest)
        from PIL import Image

        for published in list((output / "e2e" / "images").glob("*/*.webp")):
            with Image.open(published) as image:
                image.load()
                self.assertEqual("WEBP", image.format)
            self.assertEqual((1600, 900), image.size)
        self.assertFalse(list(output.rglob("*.rendering.*")))

    def test_site_builds_directly_from_compact_cache_without_reencoding(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        output = self.root / "compact-site"

        build(
            evidence_root=compact_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            require_compact=True,
        )

        manifest = json.loads(
            (compact_root / branch / "manifest.json").read_text(encoding="utf-8")
        )
        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        compact_by_id = {frame["frame_id"]: frame for frame in manifest["frames"]}
        for frame in gallery["frames"]:
            cached = compact_by_id[frame["frame_id"]]["derivative"]
            source = compact_root / branch / cached["asset"]
            published = output / "e2e" / frame["image"]
            self.assertEqual(source.read_bytes(), published.read_bytes())
            self.assertEqual(cached["file_sha256"], frame["published_file_sha256"])
            self.assertEqual(
                compact_by_id[frame["frame_id"]]["file_sha256"],
                frame["source_file_sha256"],
            )
        self.assertTrue(
            all("published_pixel_validation" in item for item in gallery["comparisons"])
        )

    def test_gallery_publishes_the_complete_per_capture_validation_record(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        output = self.root / "record-site"

        build(
            evidence_root=compact_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            require_compact=True,
        )

        manifest = json.loads(
            (compact_root / branch / "manifest.json").read_text(encoding="utf-8")
        )
        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        self.assertEqual(3, gallery["schema_version"])
        self.assertEqual(
            {"available": False, "lanes": [], "not_applicable": [], "releases": []},
            gallery["compatibility"],
        )
        release = gallery["releases"][0]
        self.assertEqual(manifest["contract_sha256"], release["contract_sha256"])
        self.assertIn(manifest["provenance"]["target"]["sha"], release["contract_url"])
        self.assertEqual(
            list(manifest["release"]["scenarios"]), release["scenarios"]
        )
        lanes = {lane["lane_id"]: lane for lane in gallery["lanes"]}
        self.assertEqual({lane["lane_id"] for lane in manifest["lanes"]}, set(lanes))
        published = {frame["frame_id"] for frame in gallery["frames"]}
        source_frames = {frame["frame_id"]: frame for frame in manifest["frames"]}
        for frame in gallery["frames"]:
            source = source_frames[frame["frame_id"]]
            self.assertEqual(source["runtime_evidence"], frame["runtime_evidence"])
            self.assertEqual(source["review_tier"], frame["review_tier"])
            self.assertEqual(
                source["derivative"]["pixel_validation"],
                frame["published_pixel_validation"],
            )
            self.assertEqual(
                frame["published_file_sha256"],
                frame["published_pixel_validation"]["file_sha256"],
            )
            lane = lanes[frame["lane_id"]]
            self.assertEqual(lane["artifact_node"], frame["artifact_node"])
            self.assertEqual(lane["scenario"], frame["scenario"])
            self.assertEqual("pass", lane["status"])
            self.assertRegex(lane["jar_sha256"], r"^[0-9a-f]{64}$")
        for comparison in gallery["comparisons"]:
            self.assertIn(comparison["first_frame_id"], published)
            self.assertIn(comparison["second_frame_id"], published)

    def test_gallery_publishes_compact_paired_mod_compatibility_evidence(self) -> None:
        branch = "fabric-and-neoforge-1.21.1"
        matrix = self.write_branch(branch, "1.21.1")
        compatibility_root = self.write_compatibility_bundle(branch)
        output = self.root / "compatibility-site"

        summary = build(
            evidence_root=self.evidence_root,
            compatibility_root=compatibility_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            optimize=False,
        )

        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        compatibility = gallery["compatibility"]
        self.assertTrue(compatibility["available"])
        self.assertEqual(summary["compatibility_lanes"], len(compatibility["lanes"]))
        self.assertGreater(summary["compatibility_lanes"], 0)
        self.assertEqual(1, summary["compatibility_images"])
        self.assertTrue(compatibility["not_applicable"])
        for lane in compatibility["lanes"]:
            self.assertEqual(2, len(lane["frames"]))
            self.assertEqual(lane["reviewed_frame_count"], len(lane["frames"]))
            self.assertRegex(lane["mod_version_id"], r"^[A-Za-z0-9_-]+$")
            for frame in lane["frames"]:
                self.assertTrue(frame["semantic_valid"])
                self.assertTrue(frame["matches_reference"])
                self.assertFalse(frame["defect"])
                for side in ("candidate", "reference"):
                    image = output / "e2e" / frame[side]["image"]
                    self.assertTrue(image.is_file())
                    self.assertEqual(
                        frame[side]["published_file_sha256"],
                        hashlib.sha256(image.read_bytes()).hexdigest(),
                    )
        page = (output / "e2e" / "index.html").read_text(encoding="utf-8")
        script = (output / "assets" / "gallery.js").read_text(encoding="utf-8")
        self.assertIn('id="compatibility-view"', page)
        self.assertIn("Runtime passed", script)
        self.assertIn("AI clean", script)
        self.assertNotIn("innerHTML", script)

        manifest_path = compatibility_root / branch / "manifest.json"
        stale = json.loads(manifest_path.read_text(encoding="utf-8"))
        stale["provenance"]["coverage_sha"] = "5" * 40
        manifest_path.write_text(json.dumps(stale), encoding="utf-8")
        with self.assertRaises(SiteBuildError):
            build(
                evidence_root=self.evidence_root,
                compatibility_root=compatibility_root,
                output=self.root / "stale-compatibility-site",
                repository="AkaNebur/Quick-Skin-Mod",
                matrix_path=matrix,
                optimize=False,
            )

    def test_compatibility_bundle_rejects_payload_and_carries_only_coverage(self) -> None:
        branch = "fabric-and-neoforge-1.21.1"
        compatibility_root = self.write_compatibility_bundle(branch)
        manifest_path = compatibility_root / branch / "manifest.json"
        original = json.loads(manifest_path.read_text(encoding="utf-8"))
        poisoned = json.loads(json.dumps(original))
        poisoned["lanes"][0]["frames"][0]["provider_explanation"] = "untrusted"
        manifest_path.write_text(json.dumps(poisoned), encoding="utf-8")
        with self.assertRaises(CompatibilityEvidenceError):
            validate_compatibility_bundle(compatibility_root, branch)

        manifest_path.write_text(json.dumps(original), encoding="utf-8")
        linked_root = self.root / "linked-compatibility"
        linked_root.symlink_to(compatibility_root, target_is_directory=True)
        with self.assertRaises(CompatibilityEvidenceError):
            validate_compatibility_bundle(linked_root, branch)

        carried_root = self.root / "carried-compatibility"
        destination = carry_compatibility_forward(
            evidence_root=compatibility_root,
            output_root=carried_root,
            branch=branch,
            coverage_sha="4" * 40,
            expected_repository="AkaNebur/Quick-Skin-Mod",
            scenario_contract_path=ROOT / "e2e" / "scenario-contract.json",
            compatibility_contract_path=ROOT / "e2e" / "mod-compatibility-contract.json",
        )
        carried = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("4" * 40, carried["provenance"]["coverage_sha"])
        self.assertEqual(
            original | {"provenance": original["provenance"] | {"coverage_sha": "4" * 40}},
            carried,
        )

    def test_compatibility_contract_drift_is_distinct_from_invalid_evidence(self) -> None:
        branch = "fabric-and-neoforge-1.21.1"
        compatibility_root = self.write_compatibility_bundle(branch)
        manifest_path = compatibility_root / branch / "manifest.json"
        stale = json.loads(manifest_path.read_text(encoding="utf-8"))
        stale["contracts"]["scenario_sha256"] = "0" * 64
        manifest_path.write_text(json.dumps(stale), encoding="utf-8")

        with self.assertRaises(CompatibilityContractDriftError):
            validate_compatibility_bundle(compatibility_root, branch)
        self.assertEqual(
            3,
            compatibility_evidence_main(
                [
                    "validate",
                    "--evidence-root",
                    str(compatibility_root),
                    "--branch",
                    branch,
                    "--only-branch",
                    "--repository",
                    "AkaNebur/Quick-Skin-Mod",
                ]
            ),
        )

        stale["contracts"]["scenario_sha256"] = "not-a-sha256"
        manifest_path.write_text(json.dumps(stale), encoding="utf-8")
        with self.assertRaises(CompatibilityEvidenceError) as raised:
            validate_compatibility_bundle(compatibility_root, branch)
        self.assertNotIsInstance(raised.exception, CompatibilityContractDriftError)

        stale["contracts"]["scenario_sha256"] = load_scenario_contract().sha256
        stale["unexpected"] = True
        manifest_path.write_text(json.dumps(stale), encoding="utf-8")
        with self.assertRaises(CompatibilityEvidenceError) as raised:
            validate_compatibility_bundle(compatibility_root, branch)
        self.assertNotIsInstance(raised.exception, CompatibilityContractDriftError)

    def test_compatibility_bundle_accepts_the_legacy_full_review_count(self) -> None:
        branch = "fabric-and-neoforge-1.21.1"
        compatibility_root = self.write_compatibility_bundle(branch)
        manifest_path = compatibility_root / branch / "manifest.json"
        legacy = json.loads(manifest_path.read_text(encoding="utf-8"))
        full_capture_count = len(load_scenario_contract().captures)
        for lane in legacy["lanes"]:
            lane["reviewed_frame_count"] = full_capture_count
        manifest_path.write_text(json.dumps(legacy), encoding="utf-8")
        with self.assertRaisesRegex(
            CompatibilityEvidenceError,
            "review count is invalid",
        ):
            validate_compatibility_bundle(compatibility_root, branch)

        legacy["schema_version"] = 1
        manifest_path.write_text(json.dumps(legacy), encoding="utf-8")
        validated = validate_compatibility_bundle(compatibility_root, branch)
        self.assertEqual(1, validated["schema_version"])
        self.assertTrue(
            all(
                lane["reviewed_frame_count"] == full_capture_count
                for lane in validated["lanes"]
            )
        )

    def test_evidence_without_runtime_evidence_still_validates_and_publishes(self) -> None:
        """A bundle created before the assertion message existed must not stall the site.

        Every release branch keeps a rolling cache produced by its own checkout. Requiring the
        newer field outright rejected each of those caches, and they are only regenerated after a
        port that can itself be gated on unrelated approvals.
        """

        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")
        manifest_path = self.evidence_root / branch / "manifest.json"
        legacy = json.loads(manifest_path.read_text(encoding="utf-8"))
        for frame in legacy["frames"]:
            del frame["runtime_evidence"]
        manifest_path.write_text(json.dumps(legacy), encoding="utf-8")

        validated = validate_bundle(
            self.evidence_root,
            branch,
            expected_repository="AkaNebur/Quick-Skin-Mod",
            expected_target_sha="2" * 40,
        )
        self.assertTrue(validated["frames"])
        self.assertNotIn("runtime_evidence", validated["frames"][0])

        compact_root = self.root / "legacy-compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        compacted = json.loads(
            (compact_root / branch / "manifest.json").read_text(encoding="utf-8")
        )
        self.assertNotIn("runtime_evidence", compacted["frames"][0])

        output = self.root / "legacy-site"
        build(
            evidence_root=compact_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            require_compact=True,
        )
        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        self.assertTrue(gallery["frames"])
        for frame in gallery["frames"]:
            self.assertNotIn("runtime_evidence", frame)
            self.assertIn("published_pixel_validation", frame)
            self.assertIn(frame["lane_id"], {lane["lane_id"] for lane in gallery["lanes"]})

    def test_capture_selection_opens_a_record_without_inline_html(self) -> None:
        page = (ROOT / "site" / "e2e" / "index.html").read_text(encoding="utf-8")
        script = (ROOT / "site" / "assets" / "gallery.js").read_text(encoding="utf-8")

        self.assertIn('id="capture-dialog"', page)
        self.assertIn('id="capture-dialog-body"', page)
        self.assertIn("showModal", script)
        self.assertNotIn("innerHTML", script)
        for field in (
            "runtime_evidence",
            "source_pixel_validation",
            "published_pixel_validation",
            "lane_id",
            "jar_sha256",
            "contract_sha256",
            "contract_url",
        ):
            self.assertIn(field, script)

    def test_protected_site_mode_rejects_a_raw_handoff(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")
        with self.assertRaises(SiteBuildError):
            build(
                evidence_root=self.evidence_root,
                output=self.root / "raw-site",
                repository="AkaNebur/Quick-Skin-Mod",
                matrix_path=matrix,
                require_compact=True,
            )

    def test_workflows_compact_before_fan_in_and_use_bounded_retention(self) -> None:
        pages = (ROOT / ".github" / "workflows" / "pages.yml").read_text(
            encoding="utf-8"
        )
        packaged = (ROOT / ".github" / "workflows" / "on-demand-e2e.yml").read_text(
            encoding="utf-8"
        )

        handoff = packaged.index("name: pages-e2e-${{ github.ref_name }}")
        self.assertIn(
            "retention-days: ${{ steps.identity.outputs.reference_retention_days }}",
            packaged[handoff : handoff + 900],
        )
        self.assertIn("--reference-retention-days", packaged)
        compact = pages.index("python3 scripts/pages/evidence.py compact")
        fan_in = pages.index("name: collected-pages-${{ matrix.branch }}", compact)
        self.assertLess(compact, fan_in)
        self.assertIn("kind_argument=(--kind raw)", pages[:compact])
        self.assertIn("input_kind_argument=(--input-kind raw)", pages[:fan_in])
        self.assertIn("--kind compact", pages[compact:fan_in])
        durable = pages.index("name: ${{ steps.cache.outputs.name }}")
        self.assertIn("retention-days: 90", pages[durable : durable + 500])
        self.assertIn("--require-compact-evidence", pages)

    def test_untrusted_project_text_never_becomes_inline_html(self) -> None:
        matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        data = json.loads(matrix.read_text(encoding="utf-8"))
        payload = "<img src=x onerror=alert(1)>"
        data["project"]["description"] = payload
        matrix.write_text(json.dumps(data), encoding="utf-8")
        output = self.root / "site-output"

        build(
            evidence_root=self.evidence_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            optimize=False,
        )

        self.assertNotIn(payload, (output / "index.html").read_text(encoding="utf-8"))
        self.assertIn(payload, (output / "site-data.json").read_text(encoding="utf-8"))
        self.assertIn("textContent", (output / "assets" / "site.js").read_text(encoding="utf-8"))
        self.assertNotIn("innerHTML", (output / "assets" / "site.js").read_text(encoding="utf-8"))

    def test_rejects_project_links_that_cannot_render_safely(self) -> None:
        matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        data = json.loads(matrix.read_text(encoding="utf-8"))
        data["project"]["homepage"] = "javascript:alert(1)"
        matrix.write_text(json.dumps(data), encoding="utf-8")

        with self.assertRaises(SiteBuildError):
            build(
                evidence_root=self.evidence_root,
                output=self.root / "unsafe-site",
                repository="AkaNebur/Quick-Skin-Mod",
                matrix_path=matrix,
                optimize=False,
            )


if __name__ == "__main__":
    unittest.main()
