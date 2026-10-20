#!/usr/bin/env python3
"""Offline Connected Stories review queue. Never fetches, approves or edits the app registry.

Input: <=1000 sanitized provider records plus <=64 manually transcribed publisher/catalog
relations. Names retrieve candidates only. Approval still requires exact edition identity,
explicit typed evidence and human review of both original/adaptation publication versions.
No API SDK or runtime dependency. See docs/connected-stories/coverage-review.md.
"""
import argparse
import json
import unicodedata
from urllib.parse import urlsplit
from pathlib import Path


def name_key(value):
    # Candidate retrieval only, never relationship evidence.
    value = unicodedata.normalize('NFKD', value).casefold().replace('ـ', '')
    return ' '.join(''.join(c if c.isalnum() else ' ' for c in value
                            if not unicodedata.combining(c)).split())


def public_url(value):
    parsed = urlsplit(value)
    return (parsed.scheme == 'https' and bool(parsed.hostname) and not parsed.username
            and not parsed.password and not parsed.query and parsed.port in (None, 443) and not parsed.fragment)


def review(document):
    records, relations = document['editions'], document['relations']
    if len(records) > 1000 or len(relations) > 64:
        raise ValueError('Review batch exceeds bounded metadata budget')
    by_id, names = {}, {}
    for row in records:
        ref = (row['format'], row['source'], row['url'])
        if ref in by_id or row['format'] not in ('MANHWA', 'TEXT_NOVEL'):
            raise ValueError('Duplicate/invalid source-qualified edition')
        if not public_url(row['publicUrl']):
            raise ValueError('Non-public provenance')
        by_id[ref] = row
        for name in set(name_key(n) for n in row.get('names', []) if n):
            if name:
                names.setdefault(name, []).append(ref)
    results = []
    for relation in relations:
        original = tuple(relation['original'].get(k) for k in ('format', 'source', 'url'))
        adaptation = tuple(relation['adaptation'].get(k) for k in ('format', 'source', 'url'))
        explicit = (relation.get('type') == 'ADAPTATION_OF' and original[0] == 'TEXT_NOVEL'
                    and adaptation[0] == 'MANHWA' and relation.get('publisherOriginal')
                    and relation.get('publisherAdaptation') and relation.get('evidence')
                    and all(public_url(u) for u in relation['evidence'])
                    and public_url(relation['publisherOriginal']) and public_url(relation['publisherAdaptation']))
        reason = 'READY_FOR_MANUAL_REVIEW' if explicit and original in by_id and adaptation in by_id else 'MISSING_TYPED_EVIDENCE_OR_EDITION'
        if relation.get('conflicts'):
            reason = 'CONFLICT_REQUIRES_REVIEW'
        results.append({'id': relation['id'], 'status': 'PENDING', 'reason': reason,
                        'original': relation['original'], 'adaptation': relation['adaptation'],
                        'evidence': relation.get('evidence', [])})
    # Report same-name cross-format hints separately. They never become approved graph edges.
    hints = []
    for name, refs in sorted(names.items()):
        if len({r[0] for r in refs}) == 2:
            hints.append({'name': name, 'reason': 'NAME_HINT_ONLY', 'editions': refs[:16]})
            if len(hints) == 100:
                break
    return {'schema': 1, 'pending': results, 'candidateHints': hints,
            'metadataRecords': len(records), 'approvedAutomatically': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.input.stat().st_size > 2_000_000:
        parser.error('Metadata input exceeds 2 MB')
    result = review(json.loads(args.input.read_text()))
    # Exclusive output creation: cannot overwrite an earlier review or bundled registry.
    with args.output.open('x') as target:
        json.dump(result, target, ensure_ascii=False, indent=2)
        target.write('\n')


if __name__ == '__main__':
    main()
