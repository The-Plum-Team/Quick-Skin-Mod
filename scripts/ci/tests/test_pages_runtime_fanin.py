from __future__ import annotations

import copy
import json
import shutil
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts' / 'ci'))

import feature_coverage as coverage
import feature_pages as pages
import ci_reuse as reuse
from test_ci_reuse import FixtureApi


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

    def json(self, endpoint):
        self.calls['json'] += 1
        return super().json(endpoint)


class PagesRuntimeFaninTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.api = CountingApi(self.root)
        reference, _ = reuse.find_reference(self.api, self.api.covered, 'e2e')
        self.api.wrapper(reference)
        runtime = reuse.runtime_source(self.api, 30, self.api.covered)
        self.manifest = {
            'repository': self.api.repository,
            'runtime_source': reference,
            'provenance': {
                key: {'run_id': str(run['id']), 'branch': run['head_branch'], 'sha': sha,
                      'run_url': f"https://github.com/{self.api.repository}/actions/runs/{run['id']}",
                      'created_at': run['created_at']}
                for key, run, sha in (
                    ('source', runtime.execution, runtime.tested_sha),
                    ('target', runtime.generation, self.api.covered))},
        }
        self.evidence = self.root / 'public-evidence'
        self.keys = sorted(target['bundle_key'] for target in coverage.inventory(coverage.DEFAULT_MATRIX)['include'])
        for key in self.keys:
            directory = self.evidence / key
            directory.mkdir(parents=True)
            self.write(key, self.manifest)
        self.api.calls.clear()

    def write(self, key, manifest):
        (self.evidence / key / 'manifest.json').write_text(json.dumps(manifest))

    def verify(self):
        return pages.verify_runtime_tree(self.api, evidence_root=self.evidence, source_sha=self.api.covered)

    def test_complete_sixteen_target_gate_authenticates_original_runtime_once_with_live_head_guards(self):
        self.assertEqual({'targets': 16, 'runtime_manifests': 16, 'runtime_sources': 1}, self.verify())
        self.assertEqual(Counter(head=2, run=2, jobs=2, artifacts=2, artifact=1, download=2, json=3), self.api.calls)
        self.assertEqual(14, sum(self.api.calls.values()))

    def test_last_target_cannot_substitute_provenance_after_other_targets_share_runtime(self):
        manifest = copy.deepcopy(self.manifest)
        manifest['provenance']['source']['sha'] = self.api.covered
        self.write(self.keys[-1], manifest)
        with self.assertRaisesRegex(ValueError, 'provenance differs'):
            self.verify()
        self.assertEqual(2, self.api.calls['run'])

    def test_last_target_cannot_substitute_its_runtime_reference(self):
        manifest = copy.deepcopy(self.manifest)
        manifest['runtime_source']['source']['head_sha'] = 'f' * 40
        self.write(self.keys[-1], manifest)
        with self.assertRaisesRegex(ValueError, 'substituted its original runtime reference'):
            self.verify()

    def test_direct_source_manifests_retain_their_separate_collector_admission(self):
        manifest = copy.deepcopy(self.manifest)
        del manifest['runtime_source']
        manifest['provenance']['coverage_sha'] = self.api.covered
        self.write(self.keys[-1], manifest)
        self.assertEqual({'targets': 16, 'runtime_manifests': 15, 'runtime_sources': 1}, self.verify())

    def test_failed_original_lane_blocks_the_entire_fanin(self):
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

    def test_live_source_move_at_final_guard_blocks_publication(self):
        with patch.object(self.api, 'current_sha', side_effect=[self.api.covered, 'f' * 40]):
            with self.assertRaisesRegex(ValueError, 'source advanced during'):
                self.verify()

    def test_missing_extra_or_symlinked_target_never_becomes_a_complete_fanin(self):
        target = self.evidence / self.keys[-1]
        displaced = self.root / 'displaced'
        target.rename(displaced)
        with self.assertRaisesRegex(ValueError, 'complete target inventory'):
            self.verify()
        target.symlink_to(displaced, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'complete target inventory'):
            self.verify()
        target.unlink()
        displaced.rename(target)
        (self.evidence / 'foreign').mkdir()
        with self.assertRaisesRegex(ValueError, 'complete target inventory'):
            self.verify()
        self.assertEqual(0, self.api.calls['run'])

    def test_manifest_symlink_is_rejected(self):
        path = self.evidence / self.keys[-1] / 'manifest.json'
        outside = self.root / 'outside.json'
        shutil.copyfile(path, outside)
        path.unlink()
        path.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'manifest must not be a symlink'):
            self.verify()

    def test_distinct_runtime_generations_do_not_share_admission_by_sha_alone(self):
        # Different ZIP timestamps must not overwrite the first wrapper's immutable bytes.
        with patch('zipfile.time.localtime', return_value=(2001, 1, 1, 0, 0, 0, 0, 1, -1)):
            self.api.wrapper(self.manifest['runtime_source'], identifier=31)
        self.assertNotEqual(self.api.archives[300], self.api.archives[310])
        manifest = copy.deepcopy(self.manifest)
        manifest['provenance']['target'].update(
            run_id='31', run_url=f'https://github.com/{self.api.repository}/actions/runs/31')
        self.write(self.keys[-1], manifest)
        self.api.calls.clear()
        self.assertEqual(2, self.verify()['runtime_sources'])
        self.assertEqual(4, self.api.calls['run'])
        self.assertEqual(26, sum(self.api.calls.values()))

    def test_api_failure_stops_fanin_instead_of_omitting_a_runtime(self):
        with patch.object(self.api, 'run', side_effect=ValueError('API quota exhausted')):
            with self.assertRaisesRegex(ValueError, 'API quota exhausted'):
                self.verify()


if __name__ == '__main__':
    unittest.main()
