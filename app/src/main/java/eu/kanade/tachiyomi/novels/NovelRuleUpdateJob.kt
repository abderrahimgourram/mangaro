package eu.kanade.tachiyomi.novels

import android.content.Context
import androidx.work.*
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

/** Signed public novel rules only; scheduled lazily after entering Novels. */
class NovelRuleUpdateJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if(!PUBLIC_PIPELINE_APPROVED || !BuildConfig.DEBUG) return@withContext Result.success()
        try {
            val client=OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false)
                .connectTimeout(10,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
            suspend fun get(path: String,max: Int): ByteArray {
                currentCoroutineContext().ensureActive()
                require(path.startsWith(ROOT) && path.toHttpUrl().host=="raw.githubusercontent.com")
                client.newCall(Request.Builder().url(path).build()).execute().use { response ->
                    check(response.isSuccessful)
                    response.body.byteStream().use { stream ->
                        val output=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(4096)
                        while(true) {currentCoroutineContext().ensureActive();val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=max);output.write(buffer,0,count)}
                        return output.toByteArray()
                    }
                }
            }
            val key=applicationContext.assets.open("novels/rules-public.der").use {it.readBytes()}
            val manifest=NovelRuleManifest.verify(get(ROOT+"published/manifest.json",8192),key)
            val envelope=get(ROOT+manifest.envelopePath,96*1024)
            manifest.verifyEnvelope(envelope,key)
            currentCoroutineContext().ensureActive()
            NovelRuleStore(applicationContext).apply {load();install(envelope)}
            Result.success()
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("MangaroNovels","Rules update rejected; using last known good rules",e)
            Result.retry()
        }
    }
    companion object {
        internal const val PUBLIC_PIPELINE_APPROVED=false
        private const val ROOT="https://raw.githubusercontent.com/abderrahimgourram/mangaro-novel-sources/main/"
        fun schedule(context: Context) {
            if(!PUBLIC_PIPELINE_APPROVED || !BuildConfig.DEBUG) return
            try {
            val request=PeriodicWorkRequestBuilder<NovelRuleUpdateJob>(24,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("novels.rules.v1",ExistingPeriodicWorkPolicy.KEEP,request)
            } catch(e: RuntimeException) {
                android.util.Log.w("MangaroNovels","Unable to schedule novel rules; packaged rules remain available",e)
            }
        }
    }
}
