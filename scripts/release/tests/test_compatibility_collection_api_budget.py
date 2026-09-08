from __future__ import annotations

import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from collections import Counter
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))

import collect_compatibility as collector


REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
SHA = "a" * 40
NAME = "mod-compatibility-review-complete-100"


def artifact(artifact_id, run_id, *, name=NAME, **changes):
    return collector.RemoteArtifact(
        artifact_id=artifact_id, run_id=run_id, name=name, size=100,
        digest=f"sha256:{artifact_id:064x}", expired=False,
        created_at=f"2026-09-08T20:00:{artifact_id % 60:02d}Z",
        head_branch="master", head_sha=SHA, **changes,
    )


def owner(run_id, **changes):
    return {
        "id": run_id, "status": "completed", "conclusion": "success",
        "event": "workflow_dispatch", "path": collector.REVIEW_WORKFLOW,
        "head_branch": "master", "head_sha": SHA,
        "head_repository": {"full_name": REPOSITORY}, **changes,
    }


class CountingApi:
    def __init__(self, artifacts, owners):
        self.artifacts = artifacts
        self.owners = owners
        self.owner_reads = []
        self.inventory_reads = []

    def list_named_artifacts(self, name):
        self.inventory_reads.append(name)
        return [item for item in self.artifacts if item.name == name]

    def get_run(self, run_id):
        self.owner_reads.append(run_id)
        value = self.owners[run_id]
        if isinstance(value, Exception):
            raise value
        return copy.deepcopy(value)


class CollectionApi(CountingApi):
    """Tiny real ZIPs exercise the collector's existing exact-ID and digest download guard."""

    download_and_extract = collector.GitHubClient.download_and_extract

    def __init__(self):
        super().__init__([], {
            100: owner(100, path=collector.SOURCE_WORKFLOW,
                       event="repository_dispatch", run_attempt=2),
            200: owner(200),
            201: owner(201, conclusion="failure"),
        })
        self.archives = {}
        self.artifact_reads = []
        self.downloads = []
        self.drift_id = None
        self.add(1, 100, "mod-compatibility-plan", {
            "mod-compatibility-plan.json": {"schema_version": 1},
        })
        self.add(2, 200, NAME, {"mod-compatibility-review-complete.json": {
            "schema_version": 1, "kind": "quick-skin-mod-compatibility-review-complete",
            "source_run_id": 100, "implementation_sha": SHA, "lane_count": 2,
        }})
        for index, lane in enumerate(("fabric-ears", "forge-ears")):
            capsule_id = 10 + index
            review_id = 200 + index
            # Attempt 2 must keep winning; owner reuse never selects a previous capsule.
            self.add(30 + index, 100,
                     f"mod-compatibility-review-input-100-{lane}-1", {"old.json": {}})
            self.add(capsule_id, 100,
                     f"mod-compatibility-review-input-100-{lane}-2",
                     {"curation-proof.json": {"capsule": capsule_id}})
            self.add(20 + index, review_id,
                     f"mod-compatibility-lane-complete-100-{lane}--{capsule_id}",
                     {"mod-compatibility-lane-complete.json": {"capsule": capsule_id}})
            self.add(40 + index, review_id, f"mod-compatibility-review-100-{lane}",
                     {"curation-proof.json": {"capsule": capsule_id}})

    def add(self, artifact_id, run_id, name, files):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for filename, payload in files.items():
                archive.writestr(filename, json.dumps(payload))
        raw = data.getvalue()
        self.archives[artifact_id] = raw
        self.artifacts.append(replace(artifact(artifact_id, run_id, name=name),
            size=len(raw), digest="sha256:" + hashlib.sha256(raw).hexdigest()))

    def list_run_artifacts(self, run_id):
        return [item for item in self.artifacts if item.run_id == run_id]

    def get_branch_sha(self, branch):
        return SHA

    def get_artifact(self, artifact_id):
        self.artifact_reads.append(artifact_id)
        value = next(item for item in self.artifacts if item.artifact_id == artifact_id)
        return replace(value, expired=True) if artifact_id == self.drift_id else value

    def _request(self, path, *, destination, maximum_bytes):
        artifact_id = int(path.split("/")[-2])
        self.downloads.append(artifact_id)
        destination.write_bytes(self.archives[artifact_id])


class CompatibilityCollectionApiBudgetTest(unittest.TestCase):
    def setUp(self):
        self.fetch = patch.object(collector, "_fetch_commits").start()
        self.ancestor = patch.object(collector, "_require_nonimpacting_ancestor").start()
        self.addCleanup(patch.stopall)

    def select(self, api, **arguments):
        return collector._select_review_artifact(api, repository=REPOSITORY,
            current_sha=SHA, repository_root=ROOT, maximum_size=1024,
            **{"name": NAME, "required_owner_sha": SHA, **arguments})

    def test_newest_valid_owner_stops_before_older_candidates(self):
        older, newest = artifact(1, 201), artifact(2, 200)
        api = CountingApi([older, newest], {
            200: owner(200), 201: collector.CollectionError("older owner must not be read"),
        })
        self.assertEqual((newest, SHA), self.select(api))
        self.assertEqual([200], api.owner_reads)

    def test_invalid_newer_owner_falls_back_without_caching_the_rejection(self):
        api = CountingApi([artifact(1, 200), artifact(2, 201)], {
            200: owner(200), 201: owner(201, status="in_progress"),
        })
        memo = {}
        for _ in range(2):
            self.assertEqual(200, self.select(api, validated_owners=memo)[0].run_id)
        self.assertEqual([201, 200, 201], api.owner_reads)
        self.assertEqual([(REPOSITORY, SHA, 200)], list(memo))

    def test_transport_failure_propagates_without_reading_older_owners(self):
        for detail in ("HTTP 403 rate limit", "HTTP 429", "timed out"):
            with self.subTest(detail=detail):
                failure = collector.CollectionError(detail)
                api = CountingApi([artifact(1, 200), artifact(2, 201)], {
                    200: owner(200), 201: failure,
                })
                memo = {}
                with self.assertRaises(collector.CollectionError) as raised:
                    self.select(api, validated_owners=memo)
                self.assertIs(failure, raised.exception)
                self.assertEqual([201], api.owner_reads)
                self.assertEqual({}, memo)

    def test_exact_capsule_precedence_and_legacy_fallback_keep_their_order(self):
        legacy_name = "mod-compatibility-lane-complete-100-fabric-ears"
        exact_name = legacy_name + "--10"
        exact = artifact(1, 200, name=exact_name)
        legacy = artifact(2, 201, name=legacy_name)
        api = CountingApi([legacy, exact], {200: owner(200), 201: owner(201)})
        self.assertEqual(exact, self.select(api, name=exact_name,
                                          fallback_name=legacy_name)[0])
        self.assertEqual([200], api.owner_reads)
        api.owners[200] = owner(200, event="pull_request")
        self.assertEqual(legacy, self.select(api, name=exact_name,
                                           fallback_name=legacy_name)[0])
        self.assertEqual([200, 200, 201], api.owner_reads)

    def test_memo_rechecks_each_artifact_and_required_owner_constraints(self):
        first = artifact(1, 200)
        api = CountingApi([first], {200: owner(200)})
        memo = {}
        self.select(api, validated_owners=memo)
        for changed, arguments in (
            (replace(first, expired=True), {}),
            (replace(first, size=1025), {}),
            (replace(first, name="wrong-name"), {}),
            (replace(first, head_sha="b" * 40), {}),
            (replace(first, head_branch="foreign"), {}),
            (first, {"required_run_id": 201}),
            (first, {"required_owner_sha": "b" * 40}),
        ):
            with self.subTest(changed=changed, arguments=arguments):
                api.artifacts = [changed]
                with self.assertRaisesRegex(collector.CollectionError, "no authenticated"):
                    self.select(api, validated_owners=memo, **arguments)
        self.assertEqual([200], api.owner_reads)
        self.assertEqual(1, self.fetch.call_count)
        self.assertEqual(1, self.ancestor.call_count)

    def test_wrong_owner_id_and_failed_ancestry_never_enter_the_memo(self):
        for wrong_id in (True, False):
            with self.subTest(wrong_id=wrong_id):
                api = CountingApi([artifact(1, 200)], {200: owner(201 if wrong_id else 200)})
                self.ancestor.side_effect = None if wrong_id else collector.CollectionError("impact")
                memo = {}
                with self.assertRaisesRegex(collector.CollectionError, "no authenticated"):
                    self.select(api, validated_owners=memo)
                self.assertEqual({}, memo)

    def collect(self, api, output):
        identity = {"source_sha": SHA, "target_sha": SHA, "branch": "master",
                    "bundle_key": "mc1.20.1"}
        rows = {"fabric-ears": {}, "forge-ears": {}}

        def build(**arguments):
            # Image/schema validation is covered separately; this boundary checks that the
            # real downloader and collector preserve the latest capsule/report bytes.
            for index, lane in enumerate(sorted(rows)):
                lane_root = arguments["lanes_root"] / lane
                expected = json.dumps({"capsule": 10 + index}).encode()
                self.assertEqual(expected, (lane_root / "capsule/curation-proof.json").read_bytes())
                self.assertEqual(expected, (lane_root / "report/curation-proof.json").read_bytes())
                self.assertEqual({"review_run_id": 200 + index},
                                 json.loads((lane_root / "metadata.json").read_text()))
            destination = arguments["output_root"] / identity["bundle_key"]
            destination.mkdir(parents=True)
            (destination / "fixture.json").write_text("{}")
            return destination

        with patch.object(collector, "_git", return_value="tree"), patch.object(
            collector, "validate_plan", return_value=(identity, rows, [])
        ), patch.object(collector, "build_bundle", side_effect=build):
            return collector.collect(api=api, repository=REPOSITORY, source_run_id=100,
                current_implementation_sha=SHA, publication_run_id=300,
                output_root=output, repository_root=ROOT)

    def test_collect_reads_each_terminal_review_owner_once_and_discards_memo_after_return(self):
        api = CollectionApi()
        with tempfile.TemporaryDirectory() as temporary:
            for invocation in range(2):
                destination, summary = self.collect(api, Path(temporary) / str(invocation))
                self.assertTrue((destination / "fixture.json").is_file())
                self.assertEqual(2, summary["lane_count"])
        self.assertEqual(Counter({100: 2, 200: 2, 201: 2}), Counter(api.owner_reads))
        # All eight selected archives still get a fresh immutable-ID check and byte download.
        self.assertEqual(Counter({item: 2 for item in (1, 2, 10, 11, 20, 21, 40, 41)}),
                         Counter(api.artifact_reads))
        self.assertEqual(Counter(api.artifact_reads), Counter(api.downloads))

    def test_owner_memo_cannot_bypass_the_report_exact_id_download_guard(self):
        api = CollectionApi()
        api.drift_id = 40
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "public"
            with self.assertRaisesRegex(collector.CollectionError, "artifact changed before download"):
                self.collect(api, output)
            self.assertFalse(output.exists())
        self.assertEqual([100, 200], api.owner_reads)
        self.assertIn(40, api.artifact_reads)
        self.assertNotIn(40, api.downloads)


if __name__ == "__main__":
    unittest.main()
