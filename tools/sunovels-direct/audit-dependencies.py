"""Read-only source/dependency inventory; no credentials or commands are executed."""
import argparse,json,re
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--sources-repo',required=True);p.add_argument('--output',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[2];sources=Path(a.sources_repo)
pattern=re.compile(r'SunovelsGateway|SUNOVELS_GATEWAY|sunovelsGateway|sunovels_gateway|mangaro-sunovels-staging|8be8f516-1ec8-43a4-9230-2bce76fb0870')
entries=[]
for name,base,dirs in [('android',root,['app/src/main','app/build.gradle.kts','.github','tools/source-rules']),('sources',sources,['sunovels_gateway','.github','tools']),('scheduler',Path.home()/'.config/systemd/user',['.']),('transient_scheduler',Path('/run/user')/str(__import__('os').getuid())/'systemd/transient',['.'])]:
 for directory in dirs:
  path=base/directory
  files=[path] if path.is_file() else path.rglob('*') if path.exists() else []
  for f in files:
   if not f.is_file() or any(x in f.parts for x in ['node_modules','.wrangler','build','__pycache__']) or f.suffix in ['.db','.gz','.jar']:continue
   try:text=f.read_text()
   except (OSError,UnicodeError):continue
   lines=[i for i,line in enumerate(text.splitlines(),1) if pattern.search(line)]
   if lines:entries.append({'area':name,'file':str(f.relative_to(base)),'lines':lines})
Path(a.output).write_text(json.dumps({'readOnly':True,'references':entries},indent=2)+'\n')
print(json.dumps({'referenceFiles':len(entries),'output':a.output}))
