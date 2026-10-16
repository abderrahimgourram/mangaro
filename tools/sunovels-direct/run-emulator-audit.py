"""Run the bounded, self-instrumented local APK exclusively on an emulator serial."""
import argparse, json, re, subprocess, time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--phase',choices=['live','restart'],required=True);p.add_argument('--output',required=True);p.add_argument('--apk');a=p.parse_args()
assert re.fullmatch(r'emulator-[0-9]+',a.serial),'Physical device serials are prohibited'
sdk=Path.home()/'Android/Sdk';adb=sdk/'platform-tools/adb';package='app.manhwaar.reader.sunovels.audit'
def command(*args,**kwargs):return subprocess.run([str(adb),'-s',a.serial,*args],text=True,capture_output=True,check=True,**kwargs)
assert command('shell','getprop','ro.kernel.qemu').stdout.strip()=='1'
out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
properties={name:command('shell','getprop',name).stdout.strip() for name in ['ro.build.version.sdk','ro.product.cpu.abi','ro.product.model']}
if a.apk:
 apk=Path(a.apk).resolve();tool=sdk/'build-tools/36.0.0/aapt2'
 badging=subprocess.run([str(tool),'dump','badging',str(apk)],capture_output=True,text=True,check=True).stdout
 assert re.search(r"package: name='"+re.escape(package)+"'",badging),'Wrong APK package'
 result=command('install','-r',str(apk),timeout=120);(out/'install.txt').write_text(result.stdout)
start=time.monotonic();process=subprocess.Popen([str(adb),'-s',a.serial,'shell','am','instrument','-w','-e','phase',a.phase,package+'/eu.kanade.tachiyomi.novels.SunovelsRuntimeInstrumentation'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
pid=None
for _ in range(30):
 candidate=subprocess.run([str(adb),'-s',a.serial,'shell','pidof',package],text=True,capture_output=True).stdout.strip().split()
 if candidate:pid=candidate[0];break
 if process.poll() is not None:break
 time.sleep(.2)
stdout=process.communicate(timeout=150)[0];(out/(a.phase+'-instrumentation.txt')).write_text(stdout)
if pid:
 logs=command('logcat','-d','-v','threadtime','--pid='+pid,'-s','MangaroSunRuntime:I','MangaroSunovels:D','AndroidRuntime:E').stdout
 (out/(a.phase+'-logcat.txt')).write_text(logs)
# Only the report produced by this runner is accepted; never infer success from adb's exit status.
reports=[]
for line in stdout.splitlines():
 text=line.split('stream=',1)[-1]
 if text.startswith('{'):
  try:
   value=json.loads(text)
   if value.get('runtime')=='ANDROID_EMULATOR':reports.append(value)
  except json.JSONDecodeError:pass
assert reports,'No completed Android runtime report'
report=reports[-1];report['device']=properties;report['serial']=a.serial;report['wallSeconds']=time.monotonic()-start
(out/(a.phase+'-result.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':report['passed'],'publicRequests':report['publicRequests'],'phase':a.phase,'results':str(out/(a.phase+'-result.json'))}))
raise SystemExit(0 if report['passed'] else 1)
