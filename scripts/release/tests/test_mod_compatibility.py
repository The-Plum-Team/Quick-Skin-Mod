from __future__ import annotations

import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import mod_compatibility  # noqa: E402
import mod_compatibility_visual  # noqa: E402
import update_mod_compatibility_lock  # noqa: E402
import visual_evidence  # noqa: E402


class _Response:
    def __init__(self, payload: bytes, url: str) -> None:
        self._stream = io.BytesIO(payload)
        self._url = url
        self.headers = {"Content-Length": str(len(payload))}

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def read(self, size: int = -1) -> bytes:
        return self._stream.read(size)

    def geturl(self) -> str:
        return self._url


class ModCompatibilityContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract_path = ROOT / "e2e" / "mod-compatibility-contract.json"
        self.payload = json.loads(self.contract_path.read_text(encoding="utf-8"))

    def write_contract(self, root: Path, payload: object) -> Path:
        path = root / "mod-compatibility-contract.json"
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        return path

    def test_lock_declares_only_the_six_supported_integrations(self) -> None:
        contract = mod_compatibility.load_contract(self.contract_path)

        self.assertEqual(
            {
                "cpm",
                "ears",
                "skin-layers-3d",
                "customnpcs",
                "essential",
                "replaymod",
            },
            {item.id for item in contract.mods},
        )
        self.assertNotIn("player-armor-stands", {item.id for item in contract.mods})
        self.assertEqual(104, sum(len(item.artifacts) for item in contract.mods))
        cpm = contract.mod("cpm")
        self.assertIsNotNone(cpm.multiplayer)
        self.assertEqual(
            (("1.21.2", "neoforge"),),
            tuple(
                (item.runtime_version, item.loader)
                for item in cpm.excluded_lanes
            ),
        )
        self.assertFalse(
            any(
                artifact.loader == "neoforge"
                and "1.21.2" in artifact.game_versions
                for artifact in cpm.artifacts
            )
        )
        skin_layers = contract.mod("skin-layers-3d")
        self.assertIsNotNone(contract.mod("ears").multiplayer)
        self.assertTrue(
            all(
                item.multiplayer is None
                for item in contract.mods
                if item.id not in {"cpm", "ears"}
            )
        )
        self.assertEqual(
            (("1.21.9", "neoforge"),),
            tuple(
                (item.runtime_version, item.loader)
                for item in skin_layers.excluded_lanes
            ),
        )
        for compatibility_mod in contract.mods:
            for artifact in compatibility_mod.artifacts:
                for locked_file in artifact.files:
                    self.assertTrue(locked_file.url.startswith("https://cdn.modrinth.com/data/"))
                    self.assertRegex(locked_file.sha256, r"^[0-9a-f]{64}$")
                    self.assertRegex(locked_file.sha512, r"^[0-9a-f]{128}$")

    def test_player_armor_stands_integration_is_fully_retired(self) -> None:
        retired = (
            ROOT
            / "common/src/legacy1_20_1/java/com/quickskin/mod/client/compat/PasCompatService.java",
            ROOT
            / "common/src/legacy1_20_1/java/com/quickskin/mod/mixin/compat/PasConfiguratorAccessor.java",
            ROOT
            / "common/src/legacy1_20_1/java/com/quickskin/mod/mixin/compat/PasConfiguratorMixin.java",
        )
        self.assertTrue(all(not path.exists() for path in retired))
        screen = (
            ROOT
            / "common/src/main/java/com/quickskin/mod/client/gui/screen/PlayerSkinMenuScreen.java"
        ).read_text(encoding="utf-8")
        self.assertNotIn("setSelectionCallback", screen)
        self.assertNotIn("isSelectionMode", screen)
        matrix = json.loads(
            (ROOT / "release/release-matrix.json").read_text(encoding="utf-8")
        )
        for artifact in matrix["artifacts"]:
            self.assertNotIn("pas", artifact["metadata"].get("suggests", {}))

    def test_plan_is_parallel_complete_and_records_every_lane(self) -> None:
        matrix_path = ROOT / "release" / "release-matrix.json"
        plan = mod_compatibility.build_plan(
            matrix_path,
            self.contract_path,
        )
        matrix = mod_compatibility.load_matrix(matrix_path)
        contract = mod_compatibility.load_contract(self.contract_path)
        base_rows = mod_compatibility.gha_matrix(
            matrix,
            "runtime",
            mod_compatibility.read_mod_version(matrix_path, matrix),
        )["include"]
        expected_lanes = {
            (row["artifact_node"], compatibility_mod.id)
            for row in base_rows
            for compatibility_mod in contract.mods
        }
        actual_lanes = {
            (lane["artifact_node"], lane.get("compatibility_mod", lane.get("mod")))
            for lane in (*plan["runnable"], *plan["not_applicable"])
        }

        self.assertEqual(expected_lanes, actual_lanes)
        self.assertEqual(
            len(expected_lanes),
            len(plan["runnable"]) + len(plan["not_applicable"]),
        )
        if plan["release_branch"] == "forge-and-fabric-1.20.1":
            self.assertEqual(11, len(plan["runnable"]))
            self.assertEqual(1, len(plan["not_applicable"]))
            self.assertEqual(
                ("forge-1.20.1", "replaymod", "not-applicable"),
                (
                    plan["not_applicable"][0]["artifact_node"],
                    plan["not_applicable"][0]["mod"],
                    plan["not_applicable"][0]["status"],
                ),
            )
        ids = [lane["id"] for lane in plan["runnable"]]
        self.assertEqual(len(ids), len(set(ids)))
        for lane in plan["runnable"]:
            scenarios = lane["scenarios"].split(",")
            self.assertEqual("mod-compatibility", scenarios[0])
            base_index = 1
            if lane["compatibility_mod"] in {"cpm", "ears"}:
                self.assertEqual("mod-compatibility-remote", scenarios[1])
                self.assertEqual("mod-compatibility-late-join", scenarios[2])
                base_index = 3
            self.assertEqual(
                ["phase0-smoke", "propagation", "propagation-live", "full"],
                scenarios[base_index:],
            )
            self.assertTrue(lane["base_evidence_name"].startswith("packaged-e2e-"))
            self.assertTrue(lane["base_evidence_name"].endswith("--release-behavior"))
            self.assertEqual(
                plan["compatibility_contract_sha256"],
                lane["compatibility_contract_sha256"],
            )

    def test_plan_binds_clean_evidence_to_the_source_run_profile(self) -> None:
        matrix_path = ROOT / "release" / "release-matrix.json"

        for matrix_kind, suffix in (
            ("pr-anchors", "--pr-behavior"),
            ("native-anchors", "--scheduled-behavior"),
        ):
            with self.subTest(matrix_kind=matrix_kind):
                plan = mod_compatibility.build_plan(
                    matrix_path,
                    self.contract_path,
                    base_matrix_kind=matrix_kind,
                )
                self.assertEqual(matrix_kind, plan["base_matrix_kind"])
                self.assertGreater(len(plan["runnable"]), 0)
                self.assertTrue(
                    all(
                        lane["base_evidence_name"].endswith(suffix)
                        for lane in plan["runnable"]
                    )
                )

        with self.assertRaisesRegex(
            mod_compatibility.CompatibilityContractError,
            "unsupported base matrix kind",
        ):
            mod_compatibility.build_plan(
                matrix_path,
                self.contract_path,
                base_matrix_kind="untrusted",
            )

    def test_contract_rejects_unknown_fields_unsafe_urls_and_ambiguous_lanes(self) -> None:
        mutations = []
        unknown = copy.deepcopy(self.payload)
        unknown["extra"] = True
        mutations.append(unknown)

        unsafe_url = copy.deepcopy(self.payload)
        unsafe_url["mods"][0]["artifacts"][0]["files"][0]["url"] = (
            "https://example.invalid/untrusted.jar"
        )
        mutations.append(unsafe_url)

        wrong_project = copy.deepcopy(self.payload)
        locked_url = wrong_project["mods"][0]["artifacts"][0]["files"][0]["url"]
        wrong_project["mods"][0]["artifacts"][0]["files"][0]["url"] = (
            locked_url.replace(wrong_project["mods"][0]["project_id"], "AbCd1234", 1)
        )
        mutations.append(wrong_project)

        ambiguous = copy.deepcopy(self.payload)
        ambiguous["mods"][0]["artifacts"].append(
            copy.deepcopy(ambiguous["mods"][0]["artifacts"][0])
        )
        mutations.append(ambiguous)

        excluded_artifact = copy.deepcopy(self.payload)
        excluded_artifact["mods"][0]["excluded_lanes"].append(
            {
                "runtime_version": "1.20.1",
                "loader": "fabric",
                "reason": "test exclusion",
            }
        )
        mutations.append(excluded_artifact)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, mutation in enumerate(mutations):
                with self.subTest(index=index):
                    path = self.write_contract(root, mutation)
                    with self.assertRaises(mod_compatibility.CompatibilityContractError):
                        mod_compatibility.load_contract(path)

    def test_contract_rejects_duplicate_json_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "lock.json"
            path.write_text(
                '{"schema_version":1,"schema_version":1}\n',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                mod_compatibility.CompatibilityContractError,
                "duplicate JSON key",
            ):
                mod_compatibility.load_contract(path)

    def test_contract_requires_exact_bounded_feature_evidence(self) -> None:
        missing = copy.deepcopy(self.payload)
        del missing["mods"][0]["evidence"]["baseline_with_mod"]

        extra = copy.deepcopy(self.payload)
        extra["mods"][0]["evidence"]["unexpected"] = "not allowed"

        malformed = copy.deepcopy(self.payload)
        malformed["mods"][0]["evidence"]["apply_local_skin_with_mod"] = (
            "multiline\ntext"
        )

        missing_regions = copy.deepcopy(self.payload)
        del missing_regions["mods"][0]["review_regions"]["baseline_with_mod"]

        malformed_regions = copy.deepcopy(self.payload)
        malformed_regions["mods"][0]["review_regions"][
            "apply_local_skin_with_mod"
        ] = [[0.8, 0.2, 0.1, 0.9]]

        missing_multiplayer = copy.deepcopy(self.payload)
        del missing_multiplayer["mods"][0]["multiplayer"]["evidence"][
            "baseline_with_mod"
        ]

        malformed_multiplayer_regions = copy.deepcopy(self.payload)
        malformed_multiplayer_regions["mods"][0]["multiplayer"]["review_regions"][
            "apply_local_skin_with_mod"
        ] = [[0.8, 0.2, 0.1, 0.9]]

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for label, mutation in (
                ("missing", missing),
                ("extra", extra),
                ("malformed", malformed),
                ("missing regions", missing_regions),
                ("malformed regions", malformed_regions),
                ("missing multiplayer evidence", missing_multiplayer),
                ("malformed multiplayer regions", malformed_multiplayer_regions),
            ):
                with self.subTest(label=label):
                    path = self.write_contract(root, mutation)
                    with self.assertRaises(
                        mod_compatibility.CompatibilityContractError
                    ):
                        mod_compatibility.load_contract(path)

    def test_explicit_updater_selects_newest_allowed_release_and_rejects_unknown_deps(
        self,
    ) -> None:
        mod = copy.deepcopy(self.payload["mods"][0])

        def upstream(version_id: str, published: str) -> dict[str, object]:
            return {
                "id": version_id,
                "version_number": f"1.0-{version_id}",
                "version_type": "release",
                "date_published": published,
                "game_versions": ["1.20.1"],
                "loaders": ["fabric"],
                "dependencies": [],
                "files": [
                    {
                        "filename": f"{version_id}.jar",
                        "url": (
                            f"https://cdn.modrinth.com/data/{mod['project_id']}"
                            f"/versions/{version_id}/{version_id}.jar"
                        ),
                        "size": 10,
                        "hashes": {"sha512": "a" * 128},
                        "primary": True,
                    }
                ],
            }

        older = upstream("AbCd1234", "2026-08-12T00:00:00Z")
        newest = upstream("EfGh5678", "2026-08-13T00:00:00Z")
        selected = update_mod_compatibility_lock.select_artifacts(
            mod, ["1.20.1"], [newest, older]
        )
        self.assertEqual(["EfGh5678"], [item["version_id"] for item in selected])

        newest["dependencies"] = [
            {
                "dependency_type": "required",
                "project_id": "ZyXw9876",
            }
        ]
        with self.assertRaisesRegex(
            mod_compatibility.CompatibilityContractError,
            "unlocked projects",
        ):
            update_mod_compatibility_lock.select_artifacts(
                mod, ["1.20.1"], [newest]
            )

    def test_authored_loader_exclusion_is_not_scheduled_or_refreshed(self) -> None:
        contract = mod_compatibility.load_contract(self.contract_path)
        with self.assertRaisesRegex(
            mod_compatibility.CompatibilityContractError,
            "excludes 1.21.2/neoforge",
        ):
            mod_compatibility.resolve_lane(
                contract,
                mod_id="cpm",
                artifact_node="neoforge-1.21.2",
                runtime_version="1.21.2",
                loader="neoforge",
            )
        with self.assertRaisesRegex(
            mod_compatibility.CompatibilityContractError,
            "excludes 1.21.9/neoforge",
        ):
            mod_compatibility.resolve_lane(
                contract,
                mod_id="skin-layers-3d",
                artifact_node="neoforge-1.21.9",
                runtime_version="1.21.9",
                loader="neoforge",
            )
        plan = mod_compatibility.build_plan(
            ROOT / "release" / "release-matrix.json", self.contract_path
        )
        if plan["release_branch"] == "fabric-and-neoforge-1.21.9":
            excluded = [
                lane
                for lane in plan["not_applicable"]
                if lane["mod"] == "skin-layers-3d" and lane["loader"] == "neoforge"
            ]
            self.assertEqual(1, len(excluded))
            self.assertIn("does not support", excluded[0]["reason"])

        mod = next(
            copy.deepcopy(item)
            for item in self.payload["mods"]
            if item["id"] == "skin-layers-3d"
        )
        self.assertFalse(
            update_mod_compatibility_lock._allowed_lane(mod, "1.21.9", "neoforge")
        )
        self.assertTrue(
            update_mod_compatibility_lock._allowed_lane(mod, "1.21.9", "fabric")
        )

        cpm = next(
            copy.deepcopy(item)
            for item in self.payload["mods"]
            if item["id"] == "cpm"
        )
        self.assertFalse(
            update_mod_compatibility_lock._allowed_lane(cpm, "1.21.2", "neoforge")
        )
        self.assertTrue(
            update_mod_compatibility_lock._allowed_lane(cpm, "1.21.2", "fabric")
        )

    def test_materialization_accepts_only_the_exact_locked_bytes(self) -> None:
        payload = b"immutable compatibility jar"
        url = "https://cdn.modrinth.com/data/AbCd1234/versions/EfGh5678/mod.jar"
        locked_file = mod_compatibility.LockedFile(
            filename="mod.jar",
            url=url,
            size=len(payload),
            sha256=hashlib.sha256(payload).hexdigest(),
            sha512=hashlib.sha512(payload).hexdigest(),
        )
        artifact = mod_compatibility.LockedArtifact(
            version_id="EfGh5678",
            version_number="1.0.0",
            version_type="release",
            published_at="2026-08-13T00:00:00Z",
            loader="fabric",
            game_versions=("1.20.1",),
            files=(locked_file,),
        )
        compatibility_mod = mod_compatibility.CompatibilityMod(
            id="sample-mod",
            name="Sample Mod",
            project_id="AbCd1234",
            install_on="client",
            loaders=("fabric",),
            allowed_version_types=("release",),
            provided_dependencies=(),
            evidence=mod_compatibility.CompatibilityEvidence(
                baseline_with_mod="Visible control",
                apply_local_skin_with_mod="Visible integration",
            ),
            review_regions=mod_compatibility.CompatibilityReviewRegions(
                baseline_with_mod=((0.25, 0.25, 0.75, 0.75),),
                apply_local_skin_with_mod=((0.25, 0.25, 0.75, 0.75),),
            ),
            multiplayer=None,
            supported_game_versions=None,
            excluded_lanes=(),
            artifacts=(artifact,),
        )
        lane = mod_compatibility.CompatibilityLane(
            contract_sha256="a" * 64,
            mod=compatibility_mod,
            artifact=artifact,
            artifact_node="fabric-1.20.1",
            runtime_version="1.20.1",
            loader="fabric",
        )

        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "materialized"
            with mock.patch.object(
                mod_compatibility.urllib.request,
                "urlopen",
                return_value=_Response(payload, url),
            ):
                outputs = mod_compatibility.materialize_lane(lane, destination)
            self.assertEqual((destination / "mod.jar",), outputs)
            self.assertEqual(payload, outputs[0].read_bytes())
            self.assertEqual(0o444, outputs[0].stat().st_mode & 0o777)

            with self.assertRaisesRegex(
                mod_compatibility.CompatibilityContractError,
                "must be fresh",
            ):
                mod_compatibility.materialize_lane(lane, destination)

        bad_artifact = mod_compatibility.LockedArtifact(
            **{**artifact.__dict__, "files": (
                mod_compatibility.LockedFile(
                    **{**locked_file.__dict__, "sha256": "0" * 64}
                ),
            )}
        )
        bad_lane = mod_compatibility.CompatibilityLane(
            **{**lane.__dict__, "artifact": bad_artifact}
        )
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
            mod_compatibility.urllib.request,
            "urlopen",
            side_effect=lambda *_args, **_kwargs: _Response(payload, url),
        ), mock.patch.object(mod_compatibility.time, "sleep"):
            with self.assertRaisesRegex(
                mod_compatibility.CompatibilityContractError,
                "hash/size mismatch",
            ):
                mod_compatibility.materialize_lane(
                    bad_lane, Path(temporary) / "rejected"
                )

    def test_visual_evidence_requires_every_locked_install_copy(self) -> None:
        contract = mod_compatibility.load_contract(self.contract_path)
        lane = mod_compatibility.resolve_lane(
            contract,
            mod_id="customnpcs",
            artifact_node="fabric-1.20.1",
            runtime_version="1.20.1",
            loader="fabric",
        )
        locked = lane.artifact.files[0]
        installed = [
            {"path": f"{root}/mods/{locked.filename}", "sha256": locked.sha256}
            for root in ("server", "client_a")
        ]

        accepted = visual_evidence.validate_installed_compatibility(
            installed,
            expected_roles={"client_a"},
            lane=lane,
            label="installed",
        )
        self.assertEqual(2, len(accepted))

        with self.assertRaisesRegex(
            visual_evidence.VisualEvidenceError,
            "every declared compatibility file/install root",
        ):
            visual_evidence.validate_installed_compatibility(
                installed[1:],
                expected_roles={"client_a"},
                lane=lane,
                label="installed",
            )

    def test_visual_artifact_inventory_is_exact_and_bounded(self) -> None:
        artifact = {
            "id": 1,
            "name": "evidence",
            "size_in_bytes": 1024,
            "digest": "sha256:" + "a" * 64,
            "run_id": 2,
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory = root / "inventory.json"
            inventory.write_text(
                json.dumps({"base": artifact, "candidate": artifact}),
                encoding="utf-8",
            )
            loaded = mod_compatibility_visual._load_inventory(inventory)
            self.assertEqual(artifact, loaded["base"])

            malformed = copy.deepcopy(artifact)
            malformed["unexpected"] = True
            inventory.write_text(
                json.dumps({"base": malformed, "candidate": artifact}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                mod_compatibility_visual.CompatibilityVisualError,
                "must contain exactly",
            ):
                mod_compatibility_visual._load_inventory(inventory)

    def test_compatibility_curation_uses_locked_feature_expectations(self) -> None:
        compatibility_mod = mod_compatibility.load_contract(
            self.contract_path
        ).mod("replaymod")

        baseline = mod_compatibility_visual._compatibility_expectation(
            compatibility_mod,
            {
                "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_BASELINE_CAPTURE,
                "expectation": "generic baseline",
            },
        )
        applied = mod_compatibility_visual._compatibility_expectation(
            compatibility_mod,
            {
                "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_APPLIED_CAPTURE,
                "expectation": "generic applied",
            },
        )

        self.assertEqual(compatibility_mod.evidence.baseline_with_mod, baseline)
        self.assertEqual(compatibility_mod.evidence.apply_local_skin_with_mod, applied)
        self.assertIn("recording indicator", baseline)
        self.assertIn("recorded Quick Skin payload", applied)

        ears = mod_compatibility.load_contract(self.contract_path).mod("ears")
        assert ears.multiplayer is not None
        remote_baseline = mod_compatibility_visual._compatibility_expectation(
            ears,
            {
                "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_REMOTE_BASELINE_CAPTURE,
                "expectation": "generic remote baseline",
            },
        )
        remote_applied = mod_compatibility_visual._compatibility_expectation(
            ears,
            {
                "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_REMOTE_APPLIED_CAPTURE,
                "expectation": "generic remote applied",
            },
        )
        self.assertEqual(ears.multiplayer.evidence.baseline_with_mod, remote_baseline)
        self.assertEqual(
            ears.multiplayer.evidence.apply_local_skin_with_mod,
            remote_applied,
        )
        self.assertIn("remote Alice", remote_applied)

        cpm = mod_compatibility.load_contract(self.contract_path).mod("cpm")
        assert cpm.multiplayer is not None
        late_join_cpm = mod_compatibility_visual._compatibility_expectation(
            cpm,
            {
                "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_LATE_JOIN_CAPTURE,
                "expectation": "generic late-join proof",
            },
        )
        late_join_ears = mod_compatibility_visual._compatibility_expectation(
            ears,
            {
                "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_LATE_JOIN_CAPTURE,
                "expectation": "generic late-join proof",
            },
        )
        self.assertIn("generic late-join proof", late_join_cpm)
        self.assertIn(cpm.multiplayer.evidence.baseline_with_mod, late_join_cpm)
        self.assertIn("generic late-join proof", late_join_ears)
        self.assertIn(
            ears.multiplayer.evidence.apply_local_skin_with_mod,
            late_join_ears,
        )

    def test_compatibility_curation_uses_mod_specific_review_regions(self) -> None:
        contract = mod_compatibility.load_contract(self.contract_path)
        generic_baseline = ((0.42, 0.34, 0.58, 0.86),)
        generic_applied = ((0.7, 0.42, 1.0, 0.98),)

        for compatibility_mod in contract.mods:
            with self.subTest(mod=compatibility_mod.id):
                baseline = mod_compatibility_visual._compatibility_review_regions(
                    compatibility_mod,
                    {
                        "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_BASELINE_CAPTURE,
                        "review_regions": generic_baseline,
                    },
                )
                applied = mod_compatibility_visual._compatibility_review_regions(
                    compatibility_mod,
                    {
                        "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_APPLIED_CAPTURE,
                        "review_regions": generic_applied,
                    },
                )

                self.assertEqual(
                    compatibility_mod.review_regions.baseline_with_mod,
                    baseline,
                )
                self.assertEqual(
                    compatibility_mod.review_regions.apply_local_skin_with_mod,
                    applied,
                )
                self.assertNotEqual(generic_applied, applied)

                if compatibility_mod.multiplayer is not None:
                    remote = mod_compatibility_visual._compatibility_review_regions(
                        compatibility_mod,
                        {
                            "capture_id": mod_compatibility_visual.MOD_COMPATIBILITY_REMOTE_APPLIED_CAPTURE,
                            "review_regions": generic_applied,
                        },
                    )
                    self.assertEqual(
                        compatibility_mod.multiplayer.review_regions.apply_local_skin_with_mod,
                        remote,
                    )
                    late_join = (
                        mod_compatibility_visual._compatibility_review_regions(
                            compatibility_mod,
                            {
                                "capture_id": (
                                    mod_compatibility_visual
                                    .MOD_COMPATIBILITY_LATE_JOIN_CAPTURE
                                ),
                                "review_regions": generic_applied,
                            },
                        )
                    )
                    expected_late_join = (
                        compatibility_mod.multiplayer.review_regions.baseline_with_mod
                        if compatibility_mod.id == "cpm"
                        else compatibility_mod.multiplayer.review_regions.apply_local_skin_with_mod
                    )
                    self.assertEqual(expected_late_join, late_join)

        replaymod = contract.mod("replaymod")
        self.assertEqual(2, len(replaymod.review_regions.baseline_with_mod))
        self.assertEqual(2, len(replaymod.review_regions.apply_local_skin_with_mod))

        essential = contract.mod("essential")
        self.assertTrue(
            any(region[2] <= 0.25 for region in essential.review_regions.baseline_with_mod)
        )

    def test_compatibility_curation_selects_only_the_feature_profile(self) -> None:
        scenario_contract = visual_evidence.load_catalog(
            ROOT / "e2e" / "scenario-contract.json"
        ).contract
        all_frames = [
            {"capture_id": capture.capture_id}
            for capture in scenario_contract.captures
        ]
        contract = mod_compatibility.load_contract(self.contract_path)
        selected = mod_compatibility_visual._select_compatibility_frames(
            all_frames,
            scenario_contract=scenario_contract,
            compatibility_mod=contract.mod("cpm"),
        )

        self.assertEqual(
            [
                "mod-compatibility.client_a.baseline_with_mod",
                "mod-compatibility.client_a.apply_local_skin_with_mod",
                "mod-compatibility-remote.client_b.observe_remote_baseline",
                "mod-compatibility-remote.client_b.observe_remote_applied",
                "mod-compatibility-late-join.client_b.observe_late_join_state",
            ],
            [frame["capture_id"] for frame in selected],
        )
        local_only = mod_compatibility_visual._select_compatibility_frames(
            all_frames,
            scenario_contract=scenario_contract,
            compatibility_mod=contract.mod("replaymod"),
        )
        self.assertEqual(
            [
                "mod-compatibility.client_a.baseline_with_mod",
                "mod-compatibility.client_a.apply_local_skin_with_mod",
            ],
            [frame["capture_id"] for frame in local_only],
        )
        with self.assertRaisesRegex(
            mod_compatibility_visual.CompatibilityVisualError,
            "coverage is incomplete",
        ):
            mod_compatibility_visual._select_compatibility_frames(
                [
                    frame
                    for frame in all_frames
                    if frame["capture_id"] != selected[-1]["capture_id"]
                ],
                scenario_contract=scenario_contract,
                compatibility_mod=contract.mod("cpm"),
            )
        with self.assertRaisesRegex(
            mod_compatibility_visual.CompatibilityVisualError,
            "coverage is incomplete",
        ):
            mod_compatibility_visual._select_compatibility_frames(
                all_frames + [selected[-1]],
                scenario_contract=scenario_contract,
                compatibility_mod=contract.mod("cpm"),
            )


if __name__ == "__main__":
    unittest.main()
