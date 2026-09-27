"""The adapter's ``verify_publication`` gate: every reused runtime generation the publication
publishes is reauthenticated once, bracketed by live head checks, before mod-base renders the site;
a generation of the same head whose evidence is not published never vetoes."""

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
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts' / 'ci'))
sys.path.insert(0, str(ROOT / 'scripts' / 'ci' / 'tests'))

import mod_base_path  # noqa: E402

mod_base_path.kit_root()

import feature_coverage as coverage  # noqa: E402
import feature_pages as pages  # noqa: E402
import ci_reuse as reuse  # noqa: E402
from test_ci_reuse import FixtureApi  # noqa: E402

PAGES_RUN = 8000


class CountingApi(FixtureApi):
    def __init__(self, root):
        self.calls = Counter()
        super().__init__(root)

    def current_sha(self):
        self.calls['head'] += 1
        return super().current_sha()

    def run(self, identifier):
        self.calls['run'] += 1
        return super().run(identifier)

    def jobs(self, run):
        self.calls['jobs'] += 1
        return super().jobs(run)

    def artifacts(self, **kwargs):
        self.calls['artifacts'] += 1
        return super().artifacts(**kwargs)

    def artifact(self, identifier):
        self.calls['artifact'] += 1
        return super().artifact(identifier)

    def download(self, metadata, destination, **kwargs):
        self.calls['download'] += 1
        return super().download(metadata, destination, **kwargs)

    def archive(self, metadata, *, maximum):
        # Descriptor downloads reach this through download(); only collected bundles count here.
        if metadata['name'].startswith('mb-collected--'):
            self.calls['collected'] += 1
        raw = self.archives[metadata['id']]
        if len(raw) > maximum:
            raise ValueError('fixture archive exceeds its bound')
        return pages.publisher.check_archive(raw, metadata)

    def json(self, endpoint):
        self.calls['json'] += 1
        if endpoint.startswith('actions/workflows/on-demand-e2e.yml/runs?event=workflow_dispatch&'):
            # The published head's generations: every master dispatch of the source workflow.
            runs = [run for run in self.runs.values()
                    if run['head_branch'] == 'master' and run['head_sha'] == self.covered]
            return {'total_count': len(runs), 'workflow_runs': copy.deepcopy(runs)}
        return super().json(endpoint)


class PagesRuntimeFaninTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.scratch = self.root / 'scratch'
        self.scratch.mkdir()
        (self.root / 'repository').mkdir()
        self.api = CountingApi(self.root / 'repository')
        reference, _ = reuse.find_reference(self.api, self.api.covered, 'e2e')
        self.reference = reference
        # test_repair_preflight.py seeds its descriptor-collision mutation from this record.
        self.manifest = {'runtime_source': reference}
        self.api.wrapper(reference)
        self.keys = sorted(target['bundle_key'] for target in coverage.inventory(coverage.DEFAULT_MATRIX)['include'])
        self.handoffs = {30: self.add_handoffs(30)}
        self.api.runs[PAGES_RUN] = {**self.api.runs[30], 'id': PAGES_RUN, 'path': '.github/workflows/pages.yml'}
        self.api.inventories[PAGES_RUN] = []
        self.promotion = {'bundles': [self.bundle(key, self.handoffs[30][key]) for key in self.keys]}
        self.api.calls.clear()

    def add_handoffs(self, generation):
        return {key: self.api.add_artifact(generation, generation * 1000 + index, f'mb-handoff--{key}--a1')['id']
                for index, key in enumerate(self.keys)}

    def bundle(self, key, selected, *, manifest_sha256='b' * 64, collected=1):
        return {'key': key, 'coverage_sha': self.api.covered, 'selected_artifact_id': selected,
                'collected_artifact_id': collected, 'collected_digest': 'sha256:' + 'a' * 64,
                'manifest_sha256': manifest_sha256}

    def fresh_generation(self, identifier):
        run = {**self.api.runs[30], 'id': identifier, 'created_at': '2026-09-20T00:00:00Z'}
        self.api.runs[identifier], self.api.inventories[identifier] = run, []
        return self.add_handoffs(identifier)

    def cache_bundle(self, key, generation, *, identifier, manifest_key=None, source_artifact=None):
        """A bundle republished from a cache: its collected artifact records ``generation``."""

        selected = 700000 + identifier
        manifest = {'key': manifest_key or key, 'repository': self.api.repository,
                    'source_artifact': {'id': selected if source_artifact is None else source_artifact},
                    'provenance': {'handoff': {'run_id': generation}, 'coverage_sha': self.api.covered}}
        manifest_raw = json.dumps(manifest, sort_keys=True).encode() + b'\n'
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('manifest.json', manifest_raw)
        artifact = self.api.add_artifact(PAGES_RUN, identifier, f'mb-collected--{key}')
        self.api.archives[identifier] = buffer.getvalue()
        artifact.update(size_in_bytes=len(buffer.getvalue()),
                        digest='sha256:' + hashlib.sha256(buffer.getvalue()).hexdigest())
        return {**self.bundle(key, selected, manifest_sha256=hashlib.sha256(manifest_raw).hexdigest(),
                              collected=identifier), 'collected_digest': artifact['digest']}

    def expire_original_runtime(self):
        for item in self.api.inventories[20]:
            if item['name'].startswith('packaged-e2e-'):
                item['expired'] = True

    def verify(self, promotion=None):
        return pages.verify_runtime_tree(self.api, self.promotion if promotion is None else promotion,
                                         source_sha=self.api.covered, scratch=self.scratch)

    def test_complete_publication_authenticates_its_reused_runtime_once_with_live_head_guards(self):
        self.assertEqual({'bundles': len(self.keys), 'generations': 1, 'collected_manifests': 0,
                          'runtime_sources': 1}, self.verify())
        self.assertEqual(Counter(head=2, json=4, artifacts=3, run=2, jobs=2, artifact=1, download=2), self.api.calls)

    def test_fresh_generation_is_left_to_the_kits_own_source_authentication(self):
        self.api.inventories[30] = [item for item in self.api.inventories[30] if item['name'] != 'reused-source-e2e']
        self.assertEqual({'bundles': len(self.keys), 'generations': 1, 'collected_manifests': 0,
                          'runtime_sources': 0}, self.verify())
        self.assertEqual(Counter(head=2, json=1, artifacts=1), self.api.calls)

    def test_an_unpublished_reused_generation_of_the_same_head_never_vetoes(self):
        # A documented full recovery (on-demand-e2e.yml -f capture_coverage=full) on an unchanged
        # head more than 7 days after the merge generation reused a pull request's run.
        fresh = self.fresh_generation(31)
        self.expire_original_runtime()
        promotion = {'bundles': [self.bundle(key, fresh[key]) for key in self.keys]}
        self.assertEqual({'bundles': len(self.keys), 'generations': 1, 'collected_manifests': 0,
                          'runtime_sources': 0}, self.verify(promotion))
        self.assertEqual(0, self.api.calls['run'])
        # The same expiry still vetoes a publication of the reused generation itself.
        with self.assertRaisesRegex(ValueError, 'missing or expired'):
            self.verify()

    def test_failed_original_lane_blocks_the_publication(self):
        self.api.job_lists[20][0]['jobs'][-1]['conclusion'] = 'failure'
        with self.assertRaises(ValueError):
            self.verify()

    def test_later_invocation_reauthenticates_a_changed_original_attempt(self):
        self.verify()
        self.api.calls.clear()
        self.api.runs[20]['run_attempt'] = 2
        with self.assertRaisesRegex(ValueError, 'stale, foreign or unsuccessful'):
            self.verify()
        self.assertEqual(2, self.api.calls['run'])

    def test_live_source_move_at_either_guard_blocks_publication(self):
        with patch.object(self.api, 'current_sha', side_effect=['f' * 40]):
            with self.assertRaisesRegex(ValueError, 'source advanced before'):
                self.verify()
        with patch.object(self.api, 'current_sha', side_effect=[self.api.covered, 'f' * 40]):
            with self.assertRaisesRegex(ValueError, 'source advanced during'):
                self.verify()

    def test_a_bundle_of_another_head_a_malformed_bundle_or_an_empty_publication_is_refused(self):
        for promotion in ({'bundles': []}, {'bundles': [{'key': 'mc1.20.1', 'coverage_sha': 'f' * 40}]}, {}):
            with self.subTest(promotion=promotion), self.assertRaisesRegex(ValueError, 'protected head'):
                self.verify(promotion)
        for field, value in (('selected_artifact_id', 0), ('collected_artifact_id', True),
                             ('manifest_sha256', 'B' * 64), ('key', None)):
            bundle = {**self.promotion['bundles'][0], field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify({'bundles': [bundle]})
        self.assertEqual(0, self.api.calls['head'])

    def test_substituted_generation_descriptor_is_rejected(self):
        forged = copy.deepcopy(self.reference)
        forged['coverage_sha'] = 'f' * 40
        self.api.inventories[30] = [item for item in self.api.inventories[30] if item['name'] != 'reused-source-e2e']
        self.api.add_descriptor(30, 300, 'reused-source-e2e', 'reused-source.json', forged)
        with self.assertRaisesRegex(ValueError, 'covers another generation'):
            self.verify()

    def test_distinct_runtime_generations_do_not_share_admission_by_sha_alone(self):
        # repair_preflight.FOCUSED_TESTS names this test.
        with patch('zipfile.time.localtime', return_value=(2001, 1, 1, 0, 0, 0, 0, 1, -1)):
            self.api.wrapper(self.reference, identifier=31)
        self.assertNotEqual(self.api.archives[300], self.api.archives[310])
        second = self.add_handoffs(31)
        promotion = {'bundles': [self.bundle(key, (self.handoffs[30] if index % 2 else second)[key])
                                 for index, key in enumerate(self.keys)]}
        self.api.calls.clear()
        self.assertEqual({'bundles': len(self.keys), 'generations': 2, 'collected_manifests': 0,
                          'runtime_sources': 2}, self.verify(promotion))
        self.assertEqual(4, self.api.calls['run'])
        # Only the generation the publication names is reauthenticated.
        self.api.calls.clear()
        self.assertEqual(1, self.verify()['runtime_sources'])
        self.assertEqual(2, self.api.calls['run'])

    def test_a_selected_artifact_of_another_key_is_refused(self):
        key, other = self.keys[:2]
        promotion = {'bundles': [self.bundle(key, self.handoffs[30][other])]}
        with self.assertRaisesRegex(ValueError, 'selected another artifact'):
            self.verify(promotion)

    def test_a_cache_of_the_only_generation_needs_no_download(self):
        promotion = {'bundles': [self.bundle(self.keys[0], 999999)]}
        self.assertEqual({'bundles': 1, 'generations': 1, 'collected_manifests': 0, 'runtime_sources': 1},
                         self.verify(promotion))
        self.assertEqual(0, self.api.calls['collected'])

    def test_any_second_generation_run_of_the_head_requires_the_collected_manifest(self):
        # An unsettled (or failed) recovery run leaves the single successful generation ambiguous
        # for a cache: only the collected manifest says which generation the cache republishes.
        self.fresh_generation(31)
        self.api.runs[31].update(status='in_progress', conclusion=None)
        promotion = {'bundles': [self.cache_bundle(self.keys[0], 30, identifier=950)]}
        self.assertEqual({'bundles': 1, 'generations': 1, 'collected_manifests': 1, 'runtime_sources': 1},
                         self.verify(promotion))
        self.api.runs[30].update(conclusion='failure')
        with self.assertRaisesRegex(ValueError, 'no successful source generation'):
            self.verify(promotion)

    def test_a_cache_among_several_generations_is_bound_through_its_collected_manifest(self):
        self.fresh_generation(31)
        self.expire_original_runtime()
        promotion = {'bundles': [self.cache_bundle(key, 31, identifier=900 + index)
                                 for index, key in enumerate(self.keys)]}
        self.assertEqual({'bundles': len(self.keys), 'generations': 1, 'collected_manifests': len(self.keys),
                          'runtime_sources': 0}, self.verify(promotion))
        self.assertEqual(len(self.keys), self.api.calls['collected'])
        self.assertEqual([], list(self.scratch.iterdir()))
        # The reused generation's own cache is reauthenticated, so its expiry still vetoes.
        promotion = {'bundles': [self.cache_bundle(self.keys[0], 30, identifier=990)]}
        with self.assertRaisesRegex(ValueError, 'missing or expired'):
            self.verify(promotion)

    def test_a_substituted_collected_manifest_or_artifact_is_refused(self):
        self.fresh_generation(31)
        key = self.keys[0]
        cases = {
            'manifest': ({**self.cache_bundle(key, 31, identifier=901), 'manifest_sha256': 'c' * 64},
                         'not the one the publication names'),
            'digest': ({**self.cache_bundle(key, 31, identifier=902), 'collected_digest': 'sha256:' + '0' * 64},
                       'not its collected artifact'),
            'name': (self.cache_bundle(key, 31, identifier=903, manifest_key=self.keys[1]),
                     "another publication's"),
            'selected': (self.cache_bundle(key, 31, identifier=904, source_artifact=5),
                         "another publication's"),
            'generation': (self.cache_bundle(key, 99, identifier=905), 'no successful generation'),
        }
        cases['foreign'] = ({**self.cache_bundle(self.keys[1], 31, identifier=906), 'key': key},
                            'not its collected artifact')
        for name, (bundle, message) in cases.items():
            with self.subTest(case=name), self.assertRaisesRegex(ValueError, message):
                self.verify({'bundles': [bundle]})

    def test_incomplete_generation_inventory_is_refused(self):
        with patch.object(self.api, 'json', return_value={'total_count': 2, 'workflow_runs': []}):
            with self.assertRaisesRegex(ValueError, 'generation inventory'):
                self.verify()
        with patch.object(self.api, 'json', return_value={'total_count': 0, 'workflow_runs': []}):
            with self.assertRaisesRegex(ValueError, 'no successful source generation'):
                self.verify()

    def test_api_failure_stops_the_publication_instead_of_skipping_a_runtime(self):
        with patch.object(self.api, 'run', side_effect=ValueError('API quota exhausted')):
            with self.assertRaisesRegex(ValueError, 'API quota exhausted'):
                self.verify()


if __name__ == '__main__':
    unittest.main()
