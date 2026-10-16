"""Read-only, allowlisted backup of accepted metadata. No source requests or private state."""
import argparse,gzip,hashlib,json,sqlite3,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--db',required=True);p.add_argument('--sources-repo',required=True);p.add_argument('--policy',required=True);p.add_argument('--output',required=True);a=p.parse_args()
sys.path.insert(0,a.sources_repo)
from sunovels_gateway.domain import metadata,canonical
policy=json.loads(Path(a.policy).read_text())
assert policy.get('metadataRedistributionApproved') is True and policy.get('metadataCollectionApproved') is True and policy.get('permissionScopeReviewed') is True and policy.get('permissionSourceId')=='novel.sunovels'
import time
assert policy.get('permissionExpiresAt') is None or policy['permissionExpiresAt']>time.time()
c=sqlite3.connect('file:'+str(Path(a.db).resolve())+'?mode=ro',uri=True);c.row_factory=sqlite3.Row;c.execute('BEGIN')
assert c.execute('PRAGMA quick_check').fetchone()[0]=='ok'
generation=json.loads(c.execute("SELECT value FROM meta WHERE key='current'").fetchone()[0]);g=dict(c.execute('SELECT * FROM generations WHERE id=?',(generation,)).fetchone());assert g['origin']=='PUBLIC_SITE'
summary=json.loads(c.execute('SELECT data FROM summaries WHERE generation=?',(generation,)).fetchone()[0])
rows=[metadata(json.loads(r['data'])) for r in c.execute('SELECT * FROM records WHERE generation=? AND missing<2 ORDER BY slug',(generation,))];assert len(rows)==summary['recordCount'];assert len({r['id'] for r in rows})==len(rows)
indexes=[]
for r in c.execute('SELECT * FROM index_stages WHERE origin=? ORDER BY slug',('PUBLIC_SITE',)):
 d=json.loads(r['data'])
 for row in d['rows']:assert canonical(row['url'],True).startswith('https://sunovels.com/novel/'+r['slug']+'/')
 indexes.append({'id':r['slug'],'observedAt':r['observed'],'index':d})
payload={'schemaVersion':1,'sourceId':'novel.sunovels','sourceGeneration':generation,'acceptedAt':g['accepted_at'],'catalogObservedAt':summary['catalogEvidence']['originalFullCatalogAt'],'recordCount':len(rows),'records':rows}
raw=json.dumps(payload,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode();compressed=gzip.compress(raw,mtime=0)
out=Path(a.output);out.mkdir(parents=True,exist_ok=True);(out/'sunovels-catalog-v1.json.gzip').write_bytes(compressed)
receipt={'schemaVersion':1,'recordCount':len(rows),'sourceGeneration':generation,'sha256':hashlib.sha256(raw).hexdigest(),'gzipSha256':hashlib.sha256(compressed).hexdigest(),'rawBytes':len(raw),'gzipBytes':len(compressed),'discoveryOnly':True}
(out/'sunovels-catalog-v1.receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
backup={'catalog':payload,'summary':summary,'chapterIndexes':indexes,'changes':[dict(r) for r in c.execute('SELECT * FROM changes ORDER BY seq')]}
b=gzip.compress(json.dumps(backup,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode(),mtime=0);(out/'accepted-metadata-backup.json.gz').write_bytes(b);assert json.loads(gzip.decompress(b))==backup
(out/'accepted-metadata-backup.sha256').write_text(hashlib.sha256(b).hexdigest()+'  accepted-metadata-backup.json.gz\n');c.rollback();c.close()
print(json.dumps(receipt))
