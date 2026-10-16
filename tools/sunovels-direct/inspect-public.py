"""Bounded read-only public diagnostics. No credentials, browser, retry, or content output."""
import argparse, hashlib, json, time, urllib.request, urllib.parse, urllib.robotparser
from pathlib import Path
from bs4 import BeautifulSoup
p=argparse.ArgumentParser();p.add_argument('--sources-repo',required=True);p.add_argument('--output',required=True);p.add_argument('--extra-search-only',action='store_true');a=p.parse_args()
import sys
sys.path.insert(0,a.sources_repo)
from tools.sunovels_public import inspect_index,inspect_reader
out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
robot=urllib.robotparser.RobotFileParser();report=[];requests=0
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args):return None
opener=urllib.request.build_opener(NoRedirect)
def get(name,path):
 global requests
 assert requests<16
 if requests:time.sleep(1.5)
 url='https://sunovels.com'+path
 assert path=='/robots.txt' or robot.can_fetch('MangaroNovelPrototype/0.1',url)
 start=time.monotonic();requests+=1
 req=urllib.request.Request(url,headers={'User-Agent':'MangaroNovelPrototype/0.1','Accept':'text/html,text/plain','Accept-Encoding':'identity'})
 with opener.open(req,timeout=20) as r:
  body=r.read(8*1024*1024+1);assert len(body)<=8*1024*1024
  item={'name':name,'url':url,'http':r.status,'bytes':len(body),'elapsedMs':round((time.monotonic()-start)*1000),'etag':r.headers.get('ETag'),'lastModified':r.headers.get('Last-Modified'),'cacheControl':r.headers.get('Cache-Control'),'sha256':hashlib.sha256(body).hexdigest()}
 raw=body.decode();doc=BeautifulSoup(raw,'html.parser');assert not doc.select_one('#challenge-form,#cf-wrapper,.challenge-running')
 (out/(name+'.html')).write_bytes(body);report.append(item);return doc,raw,item
try:
 if a.extra_search_only:
  saved=json.loads((out/'public-report.json').read_text());report=saved['records'];requests=saved['requests'];robot.parse((out/'robots.html').read_text().splitlines())
  for name,query in [('search-broad','a'),('search-empty','mangaro-no-such-work-20261013')]:
   d,raw,item=get(name,'/search?title='+urllib.parse.quote(query)+'&page=0');item['cards']=len(d.select('.list-item a[href^="/novel/"]'))
 else:
  d,raw,item=get('robots','/robots.txt');robot.parse(raw.splitlines())
  get('catalog','/library?page=0')
  for name,query in [('search-en','Reverend'),('search-ar','القس')]:
   d,raw,item=get(name,'/search?title='+urllib.parse.quote(query)+'&page=0');item['cards']=len(d.select('.list-item a[href^="/novel/"]'));item['urls']=[x['href'] for x in d.select('.list-item a[href^="/novel/"]')]
  slugs=['birth-of-the-demonic-sword','reverend-insanity','shadow-crown-the-rebirth-of-the-cursed-prince']
  for slug in slugs:
   d,raw,item=get(slug+'-index1','/novel/'+slug+'?activeTab=chapters&page=0')
   idx=inspect_index(d,'https://sunovels.com/novel/'+slug,1)
   item['index']={k:v for k,v in idx.items() if k not in ['rows','ambiguous','availability','unlabelled']};item['rows']=len(idx['rows']);item['locked']=len(idx['availability']);item['ambiguous']=len(idx['ambiguous']);item['firstRows']=idx['rows'][:2]
  for slug,page in [('birth-of-the-demonic-sword',2),('reverend-insanity',24)]:
   d,raw,item=get(slug+'-index'+str(page),'/novel/'+slug+'?activeTab=chapters&page='+str(page-1));idx=inspect_index(d,'https://sunovels.com/novel/'+slug,page);item.update(rows=len(idx['rows']),locked=len(idx['availability']),ambiguous=len(idx['ambiguous']),nextPage=idx['nextPage'])
  for slug,num in [('birth-of-the-demonic-sword','0'),('reverend-insanity','1'),('reverend-insanity','1153'),('shadow-crown-the-rebirth-of-the-cursed-prince','1')]:
   url='https://sunovels.com/novel/'+slug+'/'+num
   d,raw,item=get(slug+'-reader'+num,'/novel/'+slug+'/'+num);item['reader']=inspect_reader(d,url,raw)
finally:
 (out/'public-report.json').write_text(json.dumps({'requests':requests,'gatewayRequests':0,'records':report},ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'requests':requests,'report':str(out/'public-report.json')}))
