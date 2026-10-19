import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('check', Path(__file__).with_name('check.py'))
check = importlib.util.module_from_spec(spec); spec.loader.exec_module(check)

class HealthContracts(unittest.TestCase):
    def plan(self, url='https://mknov.com/novel/96'):
        return [{'sourceId': 'novel.mknov', 'url': url, 'title': 'Public work'}]
    def test_valid_public_plan(self):
        self.assertEqual(check.validate_plan(self.plan()), self.plan())
    def test_wrong_hosts_auth_queries_and_chapter_routes_are_rejected(self):
        for url in ['http://mknov.com/novel/96', 'https://user@mknov.com/novel/96', 'https://mknov.com.evil/novel/96',
                    'https://mknov.com/novel/96?token=x', 'https://mknov.com/novel/96/chapter/1']:
            with self.subTest(url=url), self.assertRaises(ValueError): check.validate_plan(self.plan(url))
    def test_duplicate_or_unbounded_plans_fail(self):
        for plan in [[], self.plan()*2, self.plan()*13]:
            with self.assertRaises(ValueError): check.validate_plan(plan)
    def test_missing_or_oversized_evidence_is_not_pass(self):
        for report in [{}, {'requests': 111, 'observations': [{}]}]:
            with self.assertRaises(ValueError): check.summarize(report)
    def test_corruption_does_not_become_a_monitor_pass(self):
        result = check.summarize({'requests': 2, 'observations': [{'stage': 'text', 'status': 'CORRUPTED_UPSTREAM'}]})
        self.assertEqual(result['nonPassingObservations'], 1)
        self.assertEqual(result['successfulChapterBodies'], 0)
    def test_pass_is_explicitly_scoped(self):
        result = check.summarize({'requests': 1, 'observations': [{'stage': 'text', 'status': 'PASS'}]})
        self.assertEqual(result['scope'], 'BOUNDED_SAMPLE_NOT_ALL_WORKS_OR_CHAPTERS')
        self.assertFalse(result['publication'])

if __name__ == '__main__': unittest.main()
