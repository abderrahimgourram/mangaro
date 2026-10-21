#!/usr/bin/env python3
"""Opt-in health observations through production Kotlin adapters. No signing/publication/device access."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlsplit

REPO = Path(__file__).resolve().parents[2]
DEFAULT_PLAN = REPO / 'docs/source-reliability/sample-plan.json'


def validate_plan(plan):
    if not isinstance(plan, list) or not 1 <= len(plan) <= 12:
        raise ValueError('Plan requires 1..12 public works')
    seen, counts = set(), {}
    for row in plan:
        if not isinstance(row, dict) or row.get('sourceId') not in {'novel.mknov', 'novel.seanovel'}:
            raise ValueError('Unsupported source identity')
        source, url = row['sourceId'], row.get('url', '')
        u = urlsplit(url)
        host = 'mknov.com' if source == 'novel.mknov' else 'seanovel.org'
        pattern = r'/novel/[1-9][0-9]{0,17}' if source == 'novel.mknov' else r'/novels/[a-zA-Z0-9_-]{1,240}'
        if u.scheme != 'https' or u.netloc != host or u.query or u.fragment or not re.fullmatch(pattern, u.path):
            raise ValueError('Only exact anonymous public work URLs are accepted')
        if not isinstance(row.get('title'), str) or not row['title'].strip():
            raise ValueError('Public work title missing')
        if (source, url) in seen:
            raise ValueError('Duplicate plan edition')
        seen.add((source, url)); counts[source] = counts.get(source, 0) + 1
        if counts[source] > 6:
            raise ValueError('At most six sampled works per source')
    return plan


def summarize(report):
    rows = report.get('observations', [])
    if not rows or not isinstance(report.get('requests'), int) or not 0 < report['requests'] <= 110:
        raise ValueError('Missing/invalid live evidence')
    errors = [row for row in rows if row.get('status') != 'PASS']
    return {'scope': 'BOUNDED_SAMPLE_NOT_ALL_WORKS_OR_CHAPTERS',
            'classification': 'DOCUMENTED_UPSTREAM_OR_CONTRACT_LIMITATIONS' if errors else 'SCOPED_SAMPLE_VALIDATED',
            'requests': report['requests'], 'sampledChapterBodies': sum(r.get('stage') == 'text' for r in rows),
            'successfulChapterBodies': sum(r.get('stage') == 'text' and r.get('status') == 'PASS' for r in rows),
            'nonPassingObservations': len(errors), 'androidUiAcceptance': 'NOT_TESTED', 'publication': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true', help='Explicit serial public sampling, capped at 110 requests/10 minutes')
    parser.add_argument('--plan', type=Path, default=DEFAULT_PLAN)
    parser.add_argument('--output', type=Path, required=True, help='Private local metadata-only report destination')
    args = parser.parse_args()
    plan = validate_plan(json.loads(args.plan.read_text()))
    output = args.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    command = ['./gradlew', ':app:compileDebugKotlin', ':app:testDebugUnitTest', '--rerun']
    offline = ['*MknovSourceTest', '*SeaNovelReliabilityTest', '*NovelContentQualityTest']
    cmd = command + [item for pattern in offline for item in ('--tests', pattern)] + ['--console=plain']
    log = output.with_suffix('.offline.log')
    with log.open('w') as stream:
        result = subprocess.run(cmd, cwd=REPO, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        print('TEST_INFRASTRUCTURE_OR_REGRESSION_FAILURE: inspect local offline log; live requests not started')
        return 1
    if not args.live:
        print('OFFLINE_CONTRACTS_PASSED; no network sample requested, no source-wide health claim')
        return 0
    # Resolve a private plan copy so an interrupted invocation has reproducible, validated inputs.
    copy = output.with_suffix('.plan.json'); copy.write_text(json.dumps(plan, ensure_ascii=False, indent=2))
    env = os.environ.copy(); env.update(MANGARO_NOVEL_RELIABILITY_LIVE='1',
        MANGARO_NOVEL_HEALTH_PLAN=str(copy), MANGARO_NOVEL_HEALTH_OUTPUT=str(output))
    cmd = command + ['--tests', '*NovelSourceReliabilityLiveTest', '--console=plain']
    live_started = time.time()
    with output.with_suffix('.live.log').open('w') as stream:
        result = subprocess.run(cmd, cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode or not output.is_file() or output.stat().st_mtime < live_started:
        print('TEST_INFRASTRUCTURE_OR_REGRESSION_FAILURE: missing or unsuccessful evidence')
        return 1
    summary = summarize(json.loads(output.read_text()))
    print(json.dumps(summary, indent=2))
    return 2 if summary['nonPassingObservations'] else 0


if __name__ == '__main__':
    sys.exit(main())
