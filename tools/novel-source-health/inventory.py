#!/usr/bin/env python3
"""Read-only public metadata inventory, at most25 paced requests. Never fetches chapter bodies."""
import argparse
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import tempfile
import time
from urllib import error, request
from urllib.parse import urlsplit

LIMIT = 8 * 1024 * 1024
class InventoryError(Exception): pass
class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise InventoryError('REDIRECT_REJECTED')

def approved(url):
    u = urlsplit(url)
    return (u.scheme == 'https' and not u.fragment and
            ((u.netloc == 'api.mknov.com' and u.path == '/v1/getWorks' and not u.query) or
             (u.netloc == 'seanovel.org' and (u.path, u.query) in {('/api/novels','sort=views'),('/api/search-index','')})))

def identity(value):
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        raise InventoryError('INVALID_ID')
    try: n = Decimal(value)
    except Exception: raise InventoryError('INVALID_ID') from None
    if not n.is_finite() or n <= 0 or n != n.to_integral_value() or len(str(int(n))) > 18:
        raise InventoryError('INVALID_ID')
    return str(int(n))

class Client:
    def __init__(self, opener=None):
        self.opener = opener or request.build_opener(request.ProxyHandler({}), NoRedirect())
        self.audit = []; self.last = 0; self.started = time.monotonic()
    def get(self, url, body=None):
        if not approved(url) or len(self.audit) >= 25 or time.monotonic() - self.started >= 180:
            raise InventoryError('HOST_REQUEST_OR_TIME_BUDGET')
        time.sleep(max(0, 1 - (time.monotonic() - self.last))); self.last = time.monotonic()
        row = {'url': url}; self.audit.append(row)
        headers = {'User-Agent':'MangaroNovelPrototype/0.1', 'Accept':'application/json', 'Accept-Encoding':'identity'}
        if body is not None: headers['Content-Type'] = 'application/json'
        req = request.Request(url, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            with self.opener.open(req, timeout=15) as response:
                row['httpStatus'] = response.status
                if response.status != 200 or response.headers.get_content_type() != 'application/json':
                    raise InventoryError('NON_JSON_OR_NON_200')
                if response.headers.get('Content-Encoding', 'identity') != 'identity':
                    raise InventoryError('UNSUPPORTED_ENCODING')
                length = response.headers.get('Content-Length')
                if length is not None and (not length.isdigit() or int(length) > LIMIT):
                    raise InventoryError('OVERSIZED_OR_INVALID_LENGTH')
                raw = response.read(LIMIT + 1)
                if len(raw) > LIMIT: raise InventoryError('OVERSIZED_RESPONSE')
                if length is not None and len(raw) != int(length): raise InventoryError('TRUNCATED_RESPONSE')
                row['bytes'] = len(raw)
                return json.loads(raw.decode('utf-8','strict'), parse_float=Decimal)
        except error.HTTPError as exc:
            row['httpStatus'] = exc.code; exc.close()
            raise InventoryError('UPSTREAM_ACCESS_OR_RATE_LIMIT' if exc.code in (401,403,429) else 'UPSTREAM_HTTP_FAILURE') from None
        except (error.URLError, TimeoutError, OSError, ValueError, UnicodeError):
            raise InventoryError('NETWORK_OR_JSON_FAILURE') from None

def atomic_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.inventory-', dir=path.parent)
    try:
        with os.fdopen(fd,'w') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name): os.unlink(name)

def observe(client, max_pages=20):
    if not 1 <= max_pages <= 20: raise InventoryError('PAGE_BUDGET')
    result = {'timestamp':datetime.now(timezone.utc).isoformat(), 'scope':'PUBLIC_METADATA_ONLY', 'chapterBodiesFetched':0,
              'productionPublication':False,'sources':{},'requests':client.audit}
    for source in ['novel.mknov','novel.seanovel']:
        rows = []; state = {'classification':'INSUFFICIENT_EVIDENCE'}; result['sources'][source] = state
        try:
            if source == 'novel.mknov':
                total = None
                for page in range(max_pages):
                    data = client.get('https://api.mknov.com/v1/getWorks',{'works':'views','limit':100,'offset':page*100})
                    if data.get('status') != 'success' or type(data.get('total')) is not int or data['total'] < 0:
                        raise InventoryError('CATALOG_CONTRACT')
                    if total is None: total = data['total']
                    if total != data['total']: raise InventoryError('MUTATING_TOTAL')
                    records = data.get('data')
                    if not isinstance(records,list) or len(records) != min(100,max(0,total-page*100)):
                        raise InventoryError('PAGE_COMPLETENESS')
                    for record in records:
                        key = identity(record['id']); title = record.get('name')
                        if not isinstance(title,str) or not title.strip(): raise InventoryError('MISSING_TITLE')
                        rows.append({'sourceId':source,'url':'https://mknov.com/novel/'+key,'title':title,
                                     'status':record.get('status') if isinstance(record.get('status'),str) else None})
                    if len(rows) >= total: break
                state['declaredWorks'] = total
            else:
                data = client.get('https://seanovel.org/api/novels?sort=views')
                if not isinstance(data,list) or len(data)>1000: raise InventoryError('CATALOG_CONTRACT')
                for record in data:
                    slug = record.get('slug'); title = record.get('title_ar') or record.get('title_original')
                    if not isinstance(slug,str) or not slug or len(slug)>240 or not all(c.isalnum() or c in '-_' for c in slug):
                        raise InventoryError('INVALID_SLUG')
                    if not isinstance(title,str) or not title.strip(): raise InventoryError('MISSING_TITLE')
                    rows.append({'sourceId':source,'url':'https://seanovel.org/novels/'+slug,'title':title,
                                 'status':record.get('status') if isinstance(record.get('status'),str) else None})
                state['declaredWorks'] = len(data) # Snapshot cardinality, not every website work.
            counts = Counter(r['url'] for r in rows); unique = {r['url']:r for r in rows}
            state.update(observedRows=len(rows),uniqueWorks=len(unique),duplicatesAcrossPages=sum(n-1 for n in counts.values()),
                         works=list(unique.values()),classification='SNAPSHOT_OBSERVED' if len(unique)==state['declaredWorks'] else 'INCOMPLETE_OR_MUTATING_SNAPSHOT')
        except (InventoryError, KeyError, TypeError) as exc:
            state.update(classification='INSUFFICIENT_EVIDENCE', reason=str(exc) if isinstance(exc,InventoryError) else 'CATALOG_CONTRACT',
                         observedRows=len(rows), uniqueWorks=len({r['url'] for r in rows}), works=rows)
    return result

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--max-pages',type=int,default=20);args=parser.parse_args()
    report=observe(Client(),args.max_pages);atomic_report(args.output,report)
    print(json.dumps({source:{k:v for k,v in state.items() if k!='works'} for source,state in report['sources'].items()},indent=2))
    raise SystemExit(0 if all(s['classification']=='SNAPSHOT_OBSERVED' for s in report['sources'].values()) else 2)
