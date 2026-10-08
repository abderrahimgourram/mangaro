package eu.kanade.tachiyomi.novels

import android.content.Context
import androidx.work.*
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

/** Independent namespace. Kept dormant until this private pipeline has been reviewed and published. */
class NovelRuleUpdateJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if(!PUBLIC_PIPELINE_APPROVED || !BuildConfig.DEBUG) return@withContext Result.success()
        try {
            val client=OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false)
                .connectTimeout(10,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
            fun get(path: String,max: Int): ByteArray {
                require(path.startsWith(ROOT) && path.toHttpUrl().host=="raw.githubusercontent.com")
                client.newCall(Request.Builder().url(path).build()).execute().use { response ->
                    check(response.isSuccessful)
                    response.body.byteStream().use { stream ->
                        val output=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(4096)
                        while(true) {val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=max);output.write(buffer,0,count)}
                        return output.toByteArray()
                    }
                }
            }
            val manifest=Json.parseToJsonElement(get(ROOT+"published/manifest.json",8192).decodeToString()).jsonObject
            require(manifest["engineVersion"]?.jsonPrimitive?.int==1)
            val path=manifest.string("envelopePath")
            require(Regex("published/rules-[0-9]+\\.json").matches(path))
            val envelope=get(ROOT+path,96*1024)
            require(NovelRuleSignature.sha256(envelope)==manifest.string("sha256"))
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
            val request=PeriodicWorkRequestBuilder<NovelRuleUpdateJob>(24,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("novels.rules.v1",ExistingPeriodicWorkPolicy.KEEP,request)
        }
    }
}
