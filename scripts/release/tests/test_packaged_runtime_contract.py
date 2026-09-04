from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import call, patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402


class PackagedRuntimeReportContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.game_dir = Path(self.temporary.name)
        (self.game_dir / "e2e-report").mkdir()
        (self.game_dir / "screenshots").mkdir()
        self.row = {"runtime_version": "1.21.10"}

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def report(self, scenario: str, role: str) -> dict[str, object]:
        role_contract = packaged_runtime.SCENARIO_CONTRACT.role(scenario, role)
        return {
            "version": self.row["runtime_version"],
            "role": role,
            "scenario": scenario,
            "contract_sha256": packaged_runtime.SCENARIO_CONTRACT.sha256,
            "status": "pass",
            "steps": [
                {
                    "name": step.id,
                    "status": "pass",
                    "message": "assertion passed",
                    "screenshot": (
                        f"{step.id}.png"
                        if step.capture is not None
                        else None
                    ),
                }
                for step in role_contract.steps
            ],
        }

    def write_report(self, report: dict[str, object]) -> None:
        (self.game_dir / "e2e-report" / "report.json").write_text(
            json.dumps(report),
            encoding="utf-8",
        )

    def validate(self, scenario: str, role: str) -> dict[str, object]:
        with (
            patch.object(
                packaged_runtime,
                "inspect_screenshot_for_step",
                return_value={"validated": True},
            ),
            patch.object(
                packaged_runtime,
                "compare_screenshots",
                return_value={
                    "changed_fraction": 0.5,
                    "rms_difference": 1.0,
                    "required_changed_fraction": 0.03,
                    "region": [0.30, 0.28, 0.60, 0.85],
                },
            ) as compare,
        ):
            validated = packaged_runtime.validate_report(
                self.game_dir,
                self.row,
                scenario,
                role,
            )
        if scenario == "propagation-live" and role == "client_b":
            screenshots = self.game_dir / "screenshots"
            region = (0.30, 0.28, 0.60, 0.85)
            # Every contracted observer comparison runs, in contract order, with its exact
            # threshold and region. A literal list is deliberate: the observer chain is the
            # runtime evidence for the live animation, elytra, HD cape and removal steps.
            self.assertEqual(
                [
                    call(
                        (screenshots / "observe_before.png").resolve(),
                        (screenshots / "await_live_change.png").resolve(),
                        0.03,
                        region,
                    ),
                    call(
                        (screenshots / "await_live_change.png").resolve(),
                        (screenshots / "observe_animation_frame.png").resolve(),
                        0.002,
                        region,
                    ),
                    call(
                        (screenshots / "observe_animation_frame.png").resolve(),
                        (screenshots / "observe_remote_elytra.png").resolve(),
                        0.005,
                        region,
                    ),
                    call(
                        (screenshots / "observe_remote_elytra.png").resolve(),
                        (screenshots / "observe_hd_cape.png").resolve(),
                        0.005,
                        region,
                    ),
                    call(
                        (screenshots / "observe_hd_cape.png").resolve(),
                        (screenshots / "observe_cape_removed.png").resolve(),
                        0.005,
                        region,
                    ),
                ],
                compare.call_args_list,
            )
        return validated

    def test_accepts_exact_steps_captures_assertions_hash_and_comparisons(self) -> None:
        self.write_report(self.report("propagation-live", "client_b"))

        validated = self.validate("propagation-live", "client_b")

        self.assertEqual(
            [
                "observe_before->await_live_change",
                "await_live_change->observe_animation_frame",
                "observe_animation_frame->observe_remote_elytra",
                "observe_remote_elytra->observe_hd_cape",
                "observe_hd_cape->observe_cape_removed",
            ],
            list(validated["pixel_validation"]["comparisons"]),
        )
        self.assertEqual(
            {
                "baseline",
                "observe_before",
                "await_live_change",
                "observe_animation_frame",
                "observe_remote_elytra",
                "observe_hd_cape",
                "observe_cape_removed",
            },
            set(validated["pixel_validation"]["screenshots"]),
        )

    def test_rejects_contract_step_capture_and_assertion_drift(self) -> None:
        base = self.report("propagation", "client_b")

        cases: list[tuple[str, dict[str, object]]] = []
        wrong_hash = copy.deepcopy(base)
        wrong_hash["contract_sha256"] = "0" * 64
        cases.append(("contract hash", wrong_hash))

        reordered = copy.deepcopy(base)
        reordered["steps"][0], reordered["steps"][1] = (
            reordered["steps"][1],
            reordered["steps"][0],
        )
        cases.append(("step order", reordered))

        extra_capture = copy.deepcopy(base)
        extra_capture["steps"][1]["screenshot"] = "unexpected.png"
        cases.append(("non-capture screenshot", extra_capture))

        missing_capture = copy.deepcopy(base)
        missing_capture["steps"][0]["screenshot"] = None
        cases.append(("missing screenshot", missing_capture))

        failed_assertion = copy.deepcopy(base)
        failed_assertion["steps"][1]["status"] = "fail"
        cases.append(("assertion status", failed_assertion))

        for label, report in cases:
            with self.subTest(label=label):
                self.write_report(report)
                with self.assertRaises(packaged_runtime.RuntimeFailure):
                    self.validate("propagation", "client_b")


class PackagedRuntimeTransientConnectRetryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.profile = self.root / "execution"
        self.game_dir = self.profile / "client_a"
        (self.game_dir / "e2e-report").mkdir(parents=True)
        self.row = {"runtime_version": "1.20.1"}
        self.report = {
            "version": "1.20.1",
            "role": "client_a",
            "scenario": "full",
            "contract_sha256": packaged_runtime.SCENARIO_CONTRACT.sha256,
            "status": "fail",
            "steps": [
                {
                    "name": "join_world",
                    "status": "fail",
                    "message": (
                        "category=connection_timeout; disconnected before world join; "
                        "screen=net.minecraft.class_419; "
                        "translationKeys=[disconnect.timeout]; text=[Timed out]"
                    ),
                    "screenshot": "1.20.1_00_join_disconnect_client_a.png",
                }
            ],
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_report(self, report: dict[str, object] | None = None) -> None:
        (self.game_dir / "e2e-report" / "report.json").write_text(
            json.dumps(self.report if report is None else report),
            encoding="utf-8",
        )
        (self.game_dir / "e2e-report" / "done.marker").write_text(
            "fail", encoding="utf-8"
        )

    def test_only_exact_harness_connection_timeout_is_retryable(self) -> None:
        self.write_report()
        self.assertTrue(
            packaged_runtime.is_transient_connect_timeout_report(
                self.game_dir, self.row, "full", "client_a"
            )
        )

        cases: list[tuple[str, dict[str, object]]] = []
        rejected = copy.deepcopy(self.report)
        rejected["steps"][0]["message"] = (
            "category=connection_rejected; disconnected before world join"
        )
        cases.append(("different disconnect", rejected))

        scenario_failure = copy.deepcopy(self.report)
        scenario_failure["steps"][0]["name"] = "apply_local_skin"
        cases.append(("scenario assertion", scenario_failure))

        extra_failure = copy.deepcopy(self.report)
        extra_failure["steps"].append(copy.deepcopy(extra_failure["steps"][0]))
        cases.append(("multiple failures", extra_failure))

        wrong_identity = copy.deepcopy(self.report)
        wrong_identity["contract_sha256"] = "0" * 64
        cases.append(("untrusted report", wrong_identity))

        for label, report in cases:
            with self.subTest(label=label):
                self.write_report(report)
                self.assertFalse(
                    packaged_runtime.is_transient_connect_timeout_report(
                        self.game_dir, self.row, "full", "client_a"
                    )
                )

    def test_retry_archives_and_exports_the_first_attempt_evidence(self) -> None:
        self.write_report()
        client_log = self.profile / "logs" / "client_a.log"
        client_log.parent.mkdir(parents=True)
        client_log.write_text("first attempt timed out\n", encoding="utf-8")
        screenshots = self.game_dir / "screenshots"
        screenshots.mkdir()
        (screenshots / "1.20.1_00_join_disconnect_client_a.png").write_bytes(
            b"diagnostic png"
        )

        packaged_runtime.archive_transient_connect_attempt(
            self.profile, self.game_dir, client_log, "client_a"
        )

        diagnostic = (
            self.profile
            / "diagnostics"
            / "transient-connect-attempt-1"
            / "client_a"
        )
        self.assertFalse(client_log.exists())
        self.assertTrue((diagnostic / "logs" / "client.log").is_file())
        self.assertTrue((diagnostic / "e2e-report" / "report.json").is_file())
        self.assertTrue(
            (
                diagnostic
                / "screenshots"
                / "1.20.1_00_join_disconnect_client_a.png"
            ).is_file()
        )

        client_log.write_text("second attempt passed\n", encoding="utf-8")
        (self.game_dir / "e2e-report").mkdir()
        (self.game_dir / "e2e-report" / "report.json").write_text(
            "{}", encoding="utf-8"
        )
        (self.game_dir / "e2e-report" / "done.marker").write_text(
            "pass", encoding="utf-8"
        )
        evidence = self.root / "evidence" / "profiles" / "lane"
        packaged_runtime.export_profile_evidence(
            self.profile,
            evidence,
            {"status": "pass", "profile": "profiles/lane"},
        )

        exported = (
            evidence
            / "diagnostics"
            / "transient-connect-attempt-1"
            / "client_a"
        )
        self.assertEqual(
            "first attempt timed out\n",
            (exported / "logs" / "client.log").read_text(encoding="utf-8"),
        )
        self.assertTrue((exported / "e2e-report" / "report.json").is_file())
        self.assertTrue(
            (
                exported
                / "screenshots"
                / "1.20.1_00_join_disconnect_client_a.png"
            ).is_file()
        )

    def test_retry_log_scan_ignores_only_the_expected_failed_marker(self) -> None:
        log = self.root / "client_a.log"
        log.write_text(
            "[QS-E2E] FINISHED status=fail\nvanilla connection timed out\n",
            encoding="utf-8",
        )
        packaged_runtime.scan_runtime_logs([log], require_client_pass=False)

        log.write_text(
            "[QS-E2E] FINISHED status=fail\n"
            "org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure, "fatal runtime log evidence"
        ):
            packaged_runtime.scan_runtime_logs([log], require_client_pass=False)


if __name__ == "__main__":
    unittest.main()
