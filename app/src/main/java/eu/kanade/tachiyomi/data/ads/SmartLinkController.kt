package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.lang.LocalAccountScope as Account
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/** Only local ad-navigation coordination. Never owns, cancels or writes the download queue. */
internal class SmartLinkController private constructor(private val app: Application) : Application.ActivityLifecycleCallbacks {
    companion object {
        @Volatile private var instance: SmartLinkController? = null
        fun get(context: Context): SmartLinkController = instance ?: synchronized(this) {
            instance ?: SmartLinkController(context.applicationContext as Application).also { instance=it }
        }
        fun owner(token: Account.Token = Account.capture()) = adHash("local-ad-owner",token.fileOwner)
        fun chapter(kind: String, source: String, work: String, chapter: String) = adHash(kind,source,work,chapter)
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val file=AtomicFile(File(app.noBackupFilesDir,"smartlink-v3/state.json"))
    private val json=Json { ignoreUnknownKeys=true }
    private val journal=SmartLinkJournal(read={
        if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists())
            json.decodeFromString<SmartLinkState>(file.openRead().use { it.readBytes().decodeToString() }) else SmartLinkState()
    },write={ value ->
        check(file.baseFile.parentFile!!.isDirectory || file.baseFile.parentFile!!.mkdirs())
        val bytes=json.encodeToString(SmartLinkState.serializer(),value).toByteArray()
        val stream=file.startWrite()
        try {stream.write(bytes);stream.fd.sync();file.finishWrite(stream);check(file.baseFile.readBytes().contentEquals(bytes)) { "Ad state persistence failed" }}
        catch(failure:Throwable) {file.failWrite(stream);throw failure}
    })
    val state=journal.state
    private val mutableProblem=MutableStateFlow<String?>(null)
    val problem=mutableProblem.asStateFlow()
    @Volatile private var launchingActivity: WeakReference<Activity>? = null
    @Volatile private var launchingToken: String? = null
    @Volatile private var resumed: WeakReference<Activity>? = null
    init {
        if(BuildConfig.SMARTLINK_V3_ENABLED) {
            app.registerActivityLifecycleCallbacks(this)
            scope.launch { runCatching { edit {} } }
            scope.launch {
                var wasReady=false
                Account.changes.collect { state ->
                    val transition=wasReady && !state.ready
                    wasReady=state.ready
                    safely {
                        edit {
                            if(transition || state.ready && it.state.pending?.owner?.let { p -> p!=owner(state.token) }==true) it.cancel()
                        }
                        if(state.ready && resumed?.get()!=null) returnToApp()
                    }
                }
            }
        }
    }
    private suspend fun <T> edit(change: (SmartLinkPolicy)->T): T = journal.edit(change)
    private suspend fun safely(action: suspend ()->Unit) { try {action()} catch(c: CancellationException) {throw c} catch(_:Exception) {mutableProblem.value="تعذّر حفظ تقدم الرابط الإعلاني؛ تنزيلاتك محفوظة ويمكنك المتابعة دون إعلان"} }
    suspend fun batch(request: DownloadAdRequest, token: Account.Token): UnlockBatch {
        Account.check(token)
        val result=edit { it.batch(owner(token),request) }
        Account.check(token);return result
    }
    fun observeChapter(kind: String, key: String, previouslyRead: Boolean, token: Account.Token) {
        if(!BuildConfig.SMARTLINK_V3_ENABLED) return
        scope.launch { safely { Account.check(token);edit { it.observe(owner(token),kind,key,previouslyRead) } } }
    }
    /** Called in reader-owned order after observeChapter, even on a fast one-page chapter. */
    fun completeChapter(kind: String, key: String, previouslyRead: Boolean, token: Account.Token) {
        if(!BuildConfig.SMARTLINK_V3_ENABLED) return
        scope.launch { safely { Account.check(token);edit { it.observe(owner(token),kind,key,previouslyRead);it.complete(owner(token),kind,key) } } }
    }
    fun dismissBoundary(kind: String, key: String, token: Account.Token) {
        scope.launch { safely { Account.check(token);edit { p -> p.boundary(owner(token),kind,key)?.let {p.dismiss(it,owner(token))} } } }
    }
    fun abandon(target: String?, token: Account.Token) {
        if(target==null) return
        scope.launch { safely { Account.check(token);edit { p ->
            p.state.pending?.takeIf {it.owner==owner(token) && it.target==target}?.let {p.cancel(it.token)}
        } } }
    }
    suspend fun fallback(target: String, reason: String, token: Account.Token) {
        Account.check(token);edit { it.fallback(owner(token),target,reason) };Account.check(token)
    }
    suspend fun launch(activity: Activity?, target: String, token: Account.Token): String? =
        scope.async { launchOwned(activity,target,token) }.await()
    private suspend fun launchOwned(activity: Activity?, target: String, token: Account.Token): String? {
        Account.check(token)
        if(activity==null || activity.isFinishing || activity.isDestroyed) return "تعذّر فتح المتصفح؛ يمكنك المحاولة مجددًا"
        if(!online()) return "لا يوجد اتصال بالإنترنت؛ يمكنك المتابعة دون إعلان"
        if(InMobiAds.get(app).privacyChoice()==AdPrivacyChoice.DISABLED) return "الإعلانات مرفوضة في اختيارات الخصوصية؛ يمكنك المتابعة دون إعلان"
        val navigation=Intent(Intent.ACTION_VIEW,Uri.parse(SmartLinkConfiguration.URL)).addCategory(Intent.CATEGORY_BROWSABLE)
        val manager=app.packageManager
        val browsers=manager.queryIntentActivities(Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.filter { it!=app.packageName && it!="android" }
        val browser=navigation.resolveActivity(manager)?.packageName?.takeIf {it in browsers} ?: browsers.firstOrNull()
            ?: return "لا يوجد متصفح خارجي متاح؛ يمكنك المتابعة دون إعلان"
        navigation.setPackage(browser)
        val id=UUID.randomUUID().toString()
        val denied=edit { it.canLaunch(owner(token),target,System.currentTimeMillis()) }
        if(denied!=null) return denied
        edit { Account.check(token);it.prepare(owner(token),target,id,System.currentTimeMillis()) }
        launchingActivity=WeakReference(activity);launchingToken=id
        try {
            withContext(Dispatchers.Main.immediate) { Account.check(token);activity.startActivity(navigation) }
            edit { Account.check(token);it.accepted(id,System.currentTimeMillis()) }
            return null
        } catch(c: CancellationException) { withContext(NonCancellable) { edit {it.cancel(id)} };throw c }
        catch(_:Exception) { edit {it.cancel(id)};return "تعذّر فتح الرابط الإعلاني؛ لم تُحتسب خطوة ويمكنك إعادة المحاولة" }
    }
    fun online(): Boolean {
        val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private suspend fun returnToApp() {
        val token=Account.active()
        if(!Account.changes.value.ready) return
        edit { Account.check(token);it.returned(owner(token),System.currentTimeMillis()) }
    }
    override fun onActivityStopped(activity: Activity) {
        val token=launchingToken ?: return
        if(activity!==launchingActivity?.get() || activity.isChangingConfigurations) return
        scope.launch { safely { edit {it.away(token)} } }
    }
    override fun onActivityResumed(activity: Activity) { resumed=WeakReference(activity);scope.launch {safely {returnToApp()} } }
    override fun onActivityPaused(activity: Activity) { if(resumed?.get()===activity) resumed=null }
    override fun onActivityCreated(a: Activity,b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity,b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
}
