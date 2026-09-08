from __future__ import annotations

import copy
import unittest
from unittest.mock import patch

import test_feature_coverage_consumer as fixtures

consumer = fixtures.consumer
publisher = consumer.publisher


class FeatureBaselineNoopTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.FeatureCoverageConsumerTest.setUpClass()
        cls.addClassCleanup(fixtures.FeatureCoverageConsumerTest.doClassCleanups)

    def setUp(self):
        self.fixture = fixtures.FeatureCoverageConsumerTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.api = self.fixture.api
        self.api.live = [self.fixture.base]
        self.api.queries.clear()
        self.invocations = 0

    def prepare(self):
        self.invocations += 1
        directory = self.fixture.fixture.root / f"prepare-existing-{self.invocations}"
        directory.mkdir()
        return publisher.prepare(self.api, repository=self.fixture.repository,
            source_sha=self.fixture.base, source_run_id=self.fixture.fixture.run_id,
            issuer_run_id=9100, directory=directory)

    def test_authenticated_complete_certificate_skips_reports_but_checks_public_availability(self):
        # The existing consumer still authenticates a real bounded ZIP, policy Git tree,
        # complete matrix/capture graph, issuer job and immutable artifact metadata.
        self.api.records = {name: records for name, records in self.api.records.items()
                            if not name.startswith('visual-review-')}
        with patch.object(self.api, "run", wraps=self.api.run) as runs, \
                patch.object(self.api, "current_sha", wraps=self.api.current_sha) as heads:
            self.assertIsNone(self.prepare())
        expected = [publisher.coverage.BASELINE_ARTIFACT_NAME, *[
            record['name'] for record in self.fixture.baseline['public_artifacts'].values()]]
        self.assertEqual(publisher.coverage.BASELINE_ARTIFACT_NAME, self.api.queries[0])
        self.assertCountEqual(expected, self.api.queries)
        self.assertEqual([self.fixture.artifact['id']], self.api.downloaded)
        self.assertEqual([55, 9000, 8000, 55], [call.args[0] for call in runs.call_args_list])
        self.assertEqual(2, heads.call_count)

    def test_another_source_attempt_requires_new_full_certification(self):
        self.api.runs[55]['run_attempt'] = 2
        value = self.prepare()
        self.assertEqual(2, value['source_run_attempt'])
        self.assertEqual(9100, value['issuer']['run_id'])
        self.assertEqual(17, len(self.api.downloaded))

    def test_malformed_candidate_ids_never_reach_sort_or_owner_transport(self):
        candidates = [self.fixture.artifact]
        for invalid in [None, "later", True, 0, -1, []]:
            candidates.append({**self.fixture.artifact, 'id': invalid})
            candidates.append({**self.fixture.artifact, 'workflow_run': {
                **self.fixture.artifact['workflow_run'], 'id': invalid}})
        self.api.records[publisher.coverage.BASELINE_ARTIFACT_NAME] = candidates
        with patch.object(self.api, 'run', wraps=self.api.run) as runs:
            self.assertIsNone(self.prepare())
        self.assertEqual([55, 9000, 8000, 55], [call.args[0] for call in runs.call_args_list])

    def test_expired_or_removed_public_artifacts_with_replacements_are_recertified(self):
        record = next(iter(self.fixture.baseline['public_artifacts'].values()))
        original = copy.deepcopy(self.api.records[record['name']])
        for removed in [False, True]:
            with self.subTest(removed=removed):
                replacement = {**original[0], 'id': 80000}
                self.api.records[record['name']] = ([{**original[0], 'expired': True}]
                    if not removed else []) + [replacement]
                self.api.queries.clear()
                with patch('builtins.print') as output:
                    value = self.prepare()
                output.assert_not_called()
                self.assertEqual(80000, value['public_artifacts'][next(iter(
                    self.fixture.baseline['public_artifacts']))]['id'])
                self.assertEqual(len(self.api.queries), len(set(self.api.queries)))
                self.api.records[record['name']] = copy.deepcopy(original)

    def test_missing_public_evidence_never_confirms_an_existing_certificate(self):
        record = next(iter(self.fixture.baseline['public_artifacts'].values()))
        self.api.records.pop(record['name'])
        with patch('builtins.print') as output:
            self.assertIsNone(self.prepare())
        output.assert_not_called()

    def test_public_inventory_and_owner_transport_errors_cannot_become_absence(self):
        record = next(iter(self.fixture.baseline['public_artifacts'].values()))
        for method in ['artifacts', 'run', 'jobs']:
            with self.subTest(method=method):
                original = getattr(self.api, method)
                failure = publisher.coverage.CoverageError('public API unavailable')

                def unavailable(*args, **kwargs):
                    selected = ((method == 'artifacts' and kwargs.get('name') == record['name'])
                        or (method == 'run' and args[0] == 8000)
                        or (method == 'jobs' and args[0]['id'] == 8000))
                    if selected:
                        raise failure
                    return original(*args, **kwargs)

                with patch.object(self.api, method, side_effect=unavailable):
                    with self.assertRaises(publisher.coverage.CoverageError) as raised:
                        self.prepare()
                self.assertIs(failure, raised.exception)

    def test_another_source_id_or_policy_cannot_suppress_new_certification(self):
        for field, replacement in [('source_run_id', 56), ('policy_sha256', 'f' * 64)]:
            with self.subTest(field=field):
                value = copy.deepcopy(self.fixture.baseline)
                value[field] = replacement
                self.fixture.replace(value)
                self.assertIsNotNone(self.prepare())

    def test_foreign_or_failed_issuer_does_not_authorize_noop(self):
        for changes in [{'path': '.github/workflows/build-gate.yml'}, {'conclusion': 'failure'}]:
            with self.subTest(changes=changes):
                owner = self.api.runs[9000]
                with patch.dict(owner, changes):
                    self.assertIsNotNone(self.prepare())

    def test_invalid_certificate_coverage_cannot_authorize_noop(self):
        value = copy.deepcopy(self.fixture.baseline)
        value['targets'] = value['targets'][:-1]
        self.fixture.replace(value)
        self.assertIsNotNone(self.prepare())

    def test_failed_current_runtime_is_not_hidden_by_a_valid_certificate(self):
        self.api.job_lists[55][0]['jobs'][-1]['conclusion'] = 'failure'
        with self.assertRaises(ValueError):
            self.prepare()

    def test_source_attempt_change_during_runtime_authentication_fails_closed(self):
        original = self.api.run
        calls = 0

        def run(identifier):
            nonlocal calls
            value = copy.deepcopy(original(identifier))
            if identifier == 55:
                calls += 1
                if calls == 2:
                    value['run_attempt'] = 2
            return value

        with patch.object(self.api, 'run', side_effect=run):
            with self.assertRaisesRegex(ValueError, 'source changed'):
                self.prepare()

    def test_live_head_change_never_confirms_noop(self):
        self.api.live = [self.fixture.base, self.fixture.head]
        with patch('builtins.print') as output:
            self.assertIsNone(self.prepare())
        output.assert_not_called()

    def test_transport_errors_from_inventory_owner_jobs_and_zip_propagate(self):
        for method in ['artifacts', 'run', 'jobs', 'download']:
            with self.subTest(method=method):
                original = getattr(self.api, method)

                def unavailable(*args, **kwargs):
                    selected = ((method == 'artifacts' and kwargs.get('name') == publisher.coverage.BASELINE_ARTIFACT_NAME)
                        or (method == 'run' and args[0] == 9000)
                        or (method == 'jobs' and args[0]['id'] == 9000)
                        or (method == 'download' and args[0]['id'] == self.fixture.artifact['id']))
                    if selected:
                        raise publisher.coverage.CoverageError('API unavailable')
                    return original(*args, **kwargs)

                with patch.object(self.api, method, side_effect=unavailable):
                    with self.assertRaisesRegex(ValueError, 'API unavailable'):
                        self.prepare()

    def test_historical_inventory_bound_defers_only_the_optimization(self):
        original = self.api.artifacts

        def artifacts(**kwargs):
            if kwargs.get('name') == publisher.coverage.BASELINE_ARTIFACT_NAME:
                raise publisher.ArtifactInventoryLimit('large bounded history')
            return original(**kwargs)

        with patch.object(self.api, 'artifacts', side_effect=artifacts):
            self.assertIsNotNone(self.prepare())

    def test_only_a_complete_first_page_proves_the_known_inventory_limit(self):
        api = publisher.Api(self.api.repository)
        with patch.object(api, 'json', return_value={'total_count': 101,
                'artifacts': [{'name': publisher.coverage.BASELINE_ARTIFACT_NAME}] * 100}):
            with self.assertRaises(publisher.ArtifactInventoryLimit):
                api.artifacts(name=publisher.coverage.BASELINE_ARTIFACT_NAME)
        with patch.object(api, 'json', return_value={'total_count': 101, 'artifacts': []}):
            with self.assertRaises(publisher.coverage.CoverageError) as failure:
                api.artifacts(name=publisher.coverage.BASELINE_ARTIFACT_NAME)
        self.assertNotIsInstance(failure.exception, publisher.ArtifactInventoryLimit)
        with patch.object(api, 'json', return_value={'total_count': 101, 'artifacts': [{}] * 100}):
            with self.assertRaises(publisher.coverage.CoverageError) as failure:
                api.artifacts(name=publisher.coverage.BASELINE_ARTIFACT_NAME)
        self.assertNotIsInstance(failure.exception, publisher.ArtifactInventoryLimit)


if __name__ == '__main__':
    unittest.main()
