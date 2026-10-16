"""Compile actual Kotlin source with replay I/O on the host JVM; never uses Android runtime."""
import argparse,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--fixtures',required=True);p.add_argument('--output',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[2];out=Path(a.output);out.mkdir(parents=True,exist_ok=True);base=root/'app/src/main/java/eu/kanade/tachiyomi/novels'
# Retained real responses make this gate repeatable without contacting live sources.
fixtures=Path(a.fixtures)
if not (fixtures/'catalog.html').exists() and (fixtures/'catalog.html.gz').exists():
 import gzip
 replay=out/'replay-fixtures';replay.mkdir(exist_ok=True)
 for item in fixtures.glob('*.html.gz'):(replay/item.name.removesuffix('.gz')).write_bytes(gzip.decompress(item.read_bytes()))
 fixtures=replay

cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name,version):
 matches=[x for x in (cache/group/name/version).glob('*/*.jar') if not x.name.endswith(('-sources.jar','-javadoc.jar'))]
 if matches:return str(matches[0])
 import urllib.request
 artifact=out/(name+'-'+version+'.jar')
 if not artifact.exists():urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/'+group.replace('.','/')+'/'+name+'/'+version+'/'+artifact.name,artifact)
 return str(artifact)
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.10');coroutines=jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0');compiler=jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.4.10')
compilecp=[compiler,stdlib,jar('org.jetbrains.kotlin','kotlin-script-runtime','2.4.10'),jar('org.jetbrains.kotlin','kotlin-reflect','2.4.10'),coroutines,jar('org.jetbrains','annotations','13.0')]
cp=[stdlib,coroutines,jar('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.11.0'),jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.11.0'),jar('org.jsoup','jsoup','1.23.1'),jar('com.squareup.okhttp3','okhttp-jvm','5.4.0'),jar('com.squareup.okio','okio-jvm','3.18.1')]
s=(base/'NovelSources.kt').read_text();head=s[:s.index('/** Inspected catalogue pagination')];helpers=s[s.index('internal fun JsonElement.objects()'):];head=head.replace('import android.util.Log','');head=head.replace('protected fun next(doc: Document): Boolean = hasNextNovelPage(doc)','protected fun next(doc: Document): Boolean = false')
(out/'HtmlNovelSource.kt').write_text(head+'\n'+helpers)
(out/'BuildConfig.kt').write_text('package eu.kanade.tachiyomi\nobject BuildConfig { const val DEBUG = true; const val SUNOVELS_RUNTIME_AUDIT = false; const val SUNOVELS_PRIVATE_ACCEPTANCE = false; const val SUNOVELS_OFFLINE_AUTHORIZED = true }\n')
(out/'Replay.kt').write_text('''package eu.kanade.tachiyomi.novels
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.*
import okio.buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
internal object NovelRules { fun selector(id:String,key:String,fallback:String)=fallback }
internal object SunovelsDiagnostics { fun log(event:String,url:String?=null,route:String,page:Int?=null,status:Int?=null,code:String?=null,checkpoint:Int?=null,generation:String?=null,reason:String?=null,parserKey:String?=null) {}; fun category(error:Exception)="REPLAY_ERROR" }
class ReplaySource(private val fixtures:File) {
 val calls=mutableListOf<String>(); var failPage:Int?=null; var mutateBoundary=false
 val oneShotStatuses=mutableMapOf<String,Int>(); val statuses=mutableMapOf<String,Int>(); val bodies=mutableMapOf<String,String>(); val redirects=mutableMapOf<String,String>()
 var delayMs=0L; var interruptBody=false; var timeoutUrl:String?=null
 val client=OkHttpClient.Builder().addInterceptor { chain ->
  val request=chain.request(); val u=request.url; val url=u.toString()
  check(u.isHttps && u.host=="sunovels.com") { "ALL GATEWAYS AND OTHER HOSTS UNAVAILABLE" }; synchronized(calls) { calls.add(url) }
  if(url==timeoutUrl) throw java.net.SocketTimeoutException("INJECTED_TIMEOUT")
  if(delayMs>0) Thread.sleep(delayMs)
  var status=oneShotStatuses.remove(url) ?: statuses[url] ?: 200
  val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Host replay").header("Content-Type","text/html; charset=utf-8")
  response.header("Retry-After","60")
  redirects[url]?.let { response.header("Location",it) }
  val body=bodies[url] ?: when(u.encodedPath) {
   "/conditional-contract" -> { response.header("ETag","v1").header("Cache-Control","public,max-age=0"); if(request.header("If-None-Match")=="v1") status=304; "conditional body" }
   "/blocked-contract" -> { status=403; "blocked" }
   "/redirect-contract" -> { status=302; response.header("Location","https://gateway.invalid/catalog"); "" }
   else -> {
    response.header("Cache-Control","private,no-cache,no-store,max-age=0,must-revalidate")
    val p=u.pathSegments
    val name=when(p.first()) {
     "library" -> "catalog"
     "search" -> if(u.queryParameter("title")=="Reverend") "search-en" else "search-ar"
     "novel" -> if(p.size==3) p[1]+"-reader"+p[2] else p[1]+"-index"+((u.queryParameter("page")?.toIntOrNull()?:0)+1)
     else -> error("Unknown public route")
    }
    val page=(u.queryParameter("page")?.toIntOrNull()?:0)+1
    if(p.first()=="novel" && p.size==2 && failPage==page) throw IOException("INTERRUPTED LATER PAGE")
    var raw=File(fixtures,name+".html").readText()
    if(mutateBoundary) raw=raw.replace("50 الفصل","50 الفصل changed")
    raw
   }
  }
  val responseBody = if(!interruptBody) body.toResponseBody("text/html; charset=utf-8".toMediaType()) else object : ResponseBody() {
   override fun contentType()="text/html; charset=utf-8".toMediaType()
   override fun contentLength()=body.toByteArray().size.toLong()
   override fun source():okio.BufferedSource=object : okio.Source {
    override fun timeout()=okio.Timeout.NONE
    override fun close() {}
    override fun read(sink:okio.Buffer,byteCount:Long):Long { throw IOException("INTERRUPTED_STREAM") }
   }.buffer()
  }
  response.code(status).body(responseBody).build()
 }.build()
}
''')
files=[str(root/'app/src/main/java/eu/kanade/tachiyomi/focus/FocusCoordinator.kt')]+[str(base/f) for f in ['NovelModels.kt','SeaNovelSource.kt','SeaNovelReaderDiagnostics.kt','NovelRepositoryContentValidation.kt','SunovelsSource.kt','SunovelsMobileApi.kt','SunovelsAccess.kt','SunovelsResolution.kt','NovelContent.kt','NovelChapterIndexer.kt','NovelIndexRecovery.kt','NovelIdentity.kt','NovelMetadataStore.kt','SunovelsIndexSnapshots.kt','SunovelsRequestPolicy.kt','SunovelsLocalSearch.kt','NovelHttp.kt']]+[str(out/f) for f in ['HtmlNovelSource.kt','BuildConfig.kt','Replay.kt']]+[str(root/'tools/sunovels-direct/SourceContract.kt'), str(root/'tools/sunovels-direct/MobileContract.kt')]
cmd=['java','-cp',':'.join(compilecp),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-classpath',':'.join(cp),'-Xplugin='+jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.10'),'-d',str(out/'contracts.jar')]+files
subprocess.run(cmd,check=True)
subprocess.run(['java','-Xmx384m','-cp',str(out/'contracts.jar')+':'+':'.join(cp),'eu.kanade.tachiyomi.novels.SourceContractKt',str(fixtures),str(out/'metadata'),str(root/'app/src/debug/assets/novels/sunovels-catalog-v1.json.gzip')],check=True)

mobile=root/'tools/sunovels-direct/fixtures/mobile-accepted'
if mobile.is_dir():
 subprocess.run(['java','-Xmx384m','-cp',str(out/'contracts.jar')+':'+':'.join(cp),'eu.kanade.tachiyomi.novels.MobileContractKt',str(mobile),str(mobile),str(root/'tools/sunovels-direct/fixtures')],check=True)
