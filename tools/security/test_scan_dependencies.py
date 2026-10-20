import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock
import urllib.error

spec = importlib.util.spec_from_file_location('scanner', pathlib.Path(__file__).with_name('scan_dependencies.py'))
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class DependencyAuditTest(unittest.TestCase):
    coordinate = ('com.example', 'library', '1.2.3')

    def test_exact_selected_version_and_transitive_scope_are_queried(self):
        def request(queries):
            self.assertEqual([{'package': {'ecosystem': 'Maven', 'name': 'com.example:library'},
                               'version': '1.2.3'}], queries)
            return {'results': [{}]}
        self.assertEqual([], scanner.scan({self.coordinate: {':app:releaseRuntimeClasspath'}}, request))

    def test_any_reported_vulnerability_blocks_without_severity_suppression(self):
        finding = scanner.scan({self.coordinate: {'release'}}, lambda _: {'results': [
            {'vulns': [{'id': 'GHSA-fixture'}]}]})
        self.assertEqual([{'coordinate': 'com.example:library:1.2.3', 'id': 'GHSA-fixture',
                           'scopes': ['release']}], finding)

    def test_batch_size_and_result_order_are_preserved(self):
        calls = []
        def request(queries):
            calls.append(len(queries))
            return {'results': [{} for _ in queries]}
        self.assertEqual([], scanner.scan({('g', f'a{i:03}', '1'): {'test'} for i in range(205)}, request))
        self.assertEqual([100, 100, 5], calls)

    def test_individual_package_pagination_is_followed(self):
        calls = []
        def request(queries):
            calls.append(queries)
            if len(calls) == 1:
                return {'results': [{'next_page_token': 'next'}]}
            self.assertEqual('next', queries[0]['page_token'])
            return {'results': [{'vulns': [{'id': 'found-on-page-two'}]}]}
        self.assertEqual('found-on-page-two', scanner.scan({self.coordinate: {'runtime'}}, request)[0]['id'])

    def test_incomplete_malformed_or_repeated_page_responses_fail_closed(self):
        for response in ({}, {'results': []}, {'results': [{'error': 'denied'}]},
                         {'results': [None]}, {'results': [{'unexpected_error': 'denied'}]}, {'results': [{'vulns': [{}]}]},
                         {'results': [{'vulns': 'invalid'}]},
                         {'results': [{'next_page_token': 'repeating'}]}):
            with self.subTest(response=response), self.assertRaises(scanner.ScanError):
                scanner.scan({self.coordinate: {'runtime'}}, lambda _: response)

    def test_resolution_errors_empty_inventory_and_invalid_coordinates_fail(self):
        valid = {'schema': 1, 'build': 'fixture', 'unresolved': [], 'configurations': ['release'],
                 'packages': [{'group': 'g', 'name': 'a', 'version': '1', 'scopes': ['release']}]}
        invalid = [dict(valid, unresolved=['missing']), dict(valid, packages=[]),
                   dict(valid, schema=0), dict(valid, configurations=[]),
                   dict(valid, packages=[{'group': 'g', 'name': 'a', 'version': None, 'scopes': ['release']}]),
                   dict(valid, packages=[{'group': 'g', 'name': 'a', 'version': '1', 'scopes': []}])]
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'inventory.json'
            for value in invalid:
                path.write_text(json.dumps(value))
                with self.subTest(value=value), self.assertRaises(scanner.ScanError):
                    scanner.load_packages([path])

    def test_duplicate_versions_merge_provenance_without_double_counting(self):
        with tempfile.TemporaryDirectory() as folder:
            paths = []
            for i, scope in enumerate(['debug', 'release']):
                path = pathlib.Path(folder) / f'{i}.json'
                path.write_text(json.dumps({'schema': 1, 'build': str(i), 'unresolved': [],
                    'configurations': [scope], 'packages': [dict(group='g', name='a', version='1', scopes=[scope])]}))
                paths.append(path)
            packages, coverage = scanner.load_packages(paths)
            self.assertEqual({('g', 'a', '1'): {'debug', 'release'}}, packages)
            self.assertEqual(2, len(coverage))

    def test_transport_timeout_is_bounded_and_never_becomes_empty_success(self):
        with mock.patch.object(scanner.urllib.request, 'urlopen', side_effect=TimeoutError), \
             mock.patch.object(scanner.time, 'sleep') as sleep:
            with self.assertRaises(scanner.ScanError):
                scanner.post_queries([{'version': '1'}])
            self.assertEqual(2, sleep.call_count)

    def test_permanent_http_denial_is_not_retried_or_ignored(self):
        error = urllib.error.HTTPError('https://api.osv.dev/v1/querybatch', 403, 'denied', {}, None)
        with mock.patch.object(scanner.urllib.request, 'urlopen', side_effect=error) as request, \
             self.assertRaisesRegex(scanner.ScanError, 'OSV HTTP 403'):
            scanner.post_queries([{'version': '1'}])
        self.assertEqual(1, request.call_count)

    def test_command_writes_evidence_and_exits_nonzero_for_findings_or_api_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            source = pathlib.Path(folder) / 'inventory.json'
            output = pathlib.Path(folder) / 'report.json'
            source.write_text(json.dumps({'schema': 1, 'build': 'fixture', 'unresolved': [],
                'configurations': ['release'], 'packages': [
                    dict(group='g', name='a', version='1', scopes=['release'])]}))
            argv = ['scanner', str(source), '--output', str(output)]
            with mock.patch.object(scanner.sys, 'argv', argv):
                with mock.patch.object(scanner, 'scan', return_value=[{'id': 'vulnerability'}]):
                    self.assertEqual(1, scanner.main())
                    self.assertEqual('VULNERABILITIES', json.loads(output.read_text())['status'])
                with mock.patch.object(scanner, 'scan', side_effect=scanner.ScanError('unavailable')):
                    self.assertEqual(2, scanner.main())
                    report = json.loads(output.read_text())
                    self.assertEqual('ERROR', report['status'])
                    self.assertNotIn('findings', report)


if __name__ == '__main__':
    unittest.main()
