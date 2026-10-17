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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
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
    @Volatile private var departedToken: String? = null
    private val lifecycleRevision=MutableStateFlow(0L)
    @Volatile private var resumed: WeakReference<Activity>? = null
    init {
        if(BuildConfig.SMARTLINK_V3_ENABLED) {
            app.registerActivityLifecycleCallbacks(this)
            scope.launch { safely { edit {} } }
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
    private suspend fun safely(action: suspend ()->Unit) { try {action()} catch(c: CancellationException) {throw c} catch(_:Exception) {mutableProblem.value="تعذّر حفظ تقدم الرابط الإعلاني؛ تنزيلاتك محفوظة ويمكنك الرجوع أو إعادة المحاولة"} }
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
    fun completeChapter(kind: String, key: String, previouslyRead: Boolean, token: Account.Token, end: ReaderEndDestination? = null): Job {
        if(!BuildConfig.SMARTLINK_V3_ENABLED) return Job().apply {complete()}
        return scope.launch { safely { Account.check(token);edit {
            Account.check(token)
            if(end!=null) it.completeAtEnd(owner(token),kind,key,previouslyRead,end)
            else {it.observe(owner(token),kind,key,previouslyRead);it.complete(owner(token),kind,key)}
        } } }
    }
    fun abandon(target: String?, token: Account.Token) {
        if(target==null) return
        scope.launch { safely { Account.check(token);edit { p ->
            p.state.pending?.takeIf {it.owner==owner(token) && it.target==target}?.let {p.cancel(it.token)}
        } } }
    }
    suspend fun launch(activity: Activity?, target: String, token: Account.Token): String? =
        scope.async { launchOwned(activity,target,token) }.await()
    private suspend fun launchOwned(activity: Activity?, target: String, token: Account.Token): String? {
        Account.check(token)
        if(activity==null || activity.isFinishing || activity.isDestroyed) return "تعذّر فتح المتصفح؛ يمكنك المحاولة مجددًا"
        if(!online()) return "لا يوجد اتصال بالإنترنت؛ يمكنك الرجوع أو إعادة المحاولة"
        if(!SmartLinkPrivacy.get(app).enabled()) return "الإعلانات مرفوضة في اختيارات الخصوصية؛ يمكنك الرجوع أو إعادة المحاولة"
        val navigation=Intent(Intent.ACTION_VIEW,Uri.parse(AdsterraSmartLinkConfig.SMARTLINK_URL)).addCategory(Intent.CATEGORY_BROWSABLE)
        val manager=app.packageManager
        val browsers=manager.queryIntentActivities(Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.filter { it!=app.packageName && it!="android" }
        val browser=navigation.resolveActivity(manager)?.packageName?.takeIf {it in browsers} ?: browsers.firstOrNull()
            ?: return "لا يوجد متصفح خارجي متاح؛ يمكنك الرجوع أو إعادة المحاولة"
        navigation.setPackage(browser)
        val id=UUID.randomUUID().toString()
        val denied=edit { it.canLaunch(owner(token),target,System.currentTimeMillis()) }
        if(denied!=null) return denied
        edit { Account.check(token);it.prepare(owner(token),target,id,System.currentTimeMillis()) }
        launchingActivity=WeakReference(activity);launchingToken=id;departedToken=null
        try {
            withContext(Dispatchers.Main.immediate) { Account.check(token);activity.startActivity(navigation) }
            edit { Account.check(token);it.accepted(id,System.currentTimeMillis()) }
            return null
        } catch(c: CancellationException) { withContext(NonCancellable) { edit {it.cancel(id)} };throw c }
        catch(_:Exception) { edit {it.cancel(id)};return "تعذّر فتح الرابط الإعلاني؛ لم تُحتسب خطوة ويمكنك إعادة المحاولة" }
    }
    suspend fun pendingReader(kind: String, work: String, token: Account.Token): ReaderAdTransition? {
        Account.check(token)
        if(!online()) return null
        return edit { it.state.readerTransitions[adHash(owner(token),kind)]?.takeIf { row -> row.chapterPage && row.work==work } }
    }
    suspend fun readerApplied(kind: String, work: String, destination: String, token: Account.Token) {
        Account.check(token)
        val applied=edit {
            Account.check(token)
            val old=it.state.readerTransitions[adHash(owner(token),kind)]
            it.applied(owner(token),kind,work,destination)
            old?.takeIf { row -> row.satisfied && row.work==work && row.destination==destination }
        }
        applied?.let { readerPages.applied(ReaderAdPageHost.Key(it.owner,it.kind,it.id)) }
    }
    suspend fun readerEntry(kind: String, work: String, destination: String, payload: String?, chapterKey: String, previouslyRead: Boolean, token: Account.Token): Boolean {
        if(!BuildConfig.SMARTLINK_V3_ENABLED || !SmartLinkPrivacy.get(app).enabled()) return true
        Account.check(token)
        val pending=edit { Account.check(token);it.entry(owner(token),kind,work,destination,payload,chapterKey,previouslyRead) } ?: return true
        return readerTransition(kind,work,pending.from,pending.destination,pending.payload,token)
    }
    /** Called by actual navigation before loading the next chapter. No browser opens here.
     * The journal stores destination/evidence; only the explicit full-screen gate button launches a link.
     */
    suspend fun readerTransition(kind: String, work: String, from: String?, destination: String, payload: String?, token: Account.Token): Boolean {
        if(!BuildConfig.SMARTLINK_V3_ENABLED) return true
        Account.check(token)
        if(!SmartLinkPrivacy.get(app).enabled()) return true
        val transition=edit { Account.check(token);it.transition(owner(token),kind,work,from,destination,payload) } ?: return true
        return presentReaderPage(transition,token)
    }
    private val readerPages = ReaderAdPageHost()
    internal fun attachReaderPage(key: ReaderAdPageHost.Key, session: String) = readerPages.attach(key, session)
    internal fun closeReaderPage(key: ReaderAdPageHost.Key, session: String) {
        val account = Account.changes.value
        val verified = account.ready && owner(account.token) == key.owner &&
            SmartLinkReaderResolution.verified(state.value, key)
        readerPages.close(key, session, verified)
        if(!verified && account.ready && owner(account.token)==key.owner) abandon(key.boundary,account.token)
    }
    internal suspend fun currentReaderPage(key: ReaderAdPageHost.Key, token: Account.Token): ReaderAdTransition? {
        Account.check(token)
        return edit { Account.check(token);it.state.readerTransitions[adHash(key.owner,key.kind)]
            ?.takeIf { row -> owner(token)==key.owner && row.id==key.boundary } }
    }
    suspend fun readerPage(kind: String, work: String, from: String?, destination: String, payload: String?,
        boundaries: List<ReaderAdPageBoundary>, token: Account.Token, downloaded: Boolean = false): Boolean {
        if(!BuildConfig.SMARTLINK_V3_ENABLED || !SmartLinkPrivacy.get(app).enabled()) return true
        Account.check(token)
        if(!ReaderAdPages.required(online(),downloaded)) return true
        while(true) {
            if(!online()) return true
            val transition=edit { Account.check(token);it.pageRoute(owner(token),kind,work,from,destination,payload,boundaries) }
                ?: return true
            if(!presentReaderPage(transition,token)) return !online()
        }
    }
    private suspend fun presentReaderPage(transition: ReaderAdTransition, token: Account.Token): Boolean {
        if(transition.satisfied) return true
        Account.check(token)
        val activity=withTimeoutOrNull(2_000) { lifecycleRevision.map { resumed?.get() }.filterNotNull()
            .first { !it.isFinishing && !it.isDestroyed } } ?: return false
        return showReaderGate(activity,transition,transition.destination,transition.kind,token) == true
    }
    private suspend fun showReaderGate(activity: Activity, transition: ReaderAdTransition, destination: String, kind: String, token: Account.Token): Boolean =
        withContext(Dispatchers.Main.immediate) {
            Account.check(token)
            val key=ReaderAdPageHost.Key(transition.owner,kind,transition.id)
            val current=currentReaderPage(key,token)
                ?: return@withContext SmartLinkReaderResolution.verified(state.value,key,destination)
            if(current.satisfied) return@withContext current.destination==destination
            val request=readerPages.request(key)
            if(request.launch) try {
                activity.startActivity(ReaderAdPageActivity.intent(activity,key,request.page.session))
            } catch(_:Exception) {
                readerPages.close(key,request.page.session,false)
                mutableProblem.value="تعذّر فتح فاصل الإعلان؛ أعد المحاولة"
            }
            val passed=request.page.result.await()
            Account.check(token)
            val verified=passed && SmartLinkReaderResolution.verified(state.value,key,destination)
            if(verified) readerPages.applied(key)
            verified
        }
    fun online(): Boolean {
        val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private suspend fun returnToApp(expected: String? = departedToken ?: state.value.pending?.takeIf { it.away }?.token) {
        val token=Account.active()
        if(!Account.changes.value.ready || expected == null || resumed?.get() == null) return
        val departed = expected == departedToken
        edit {
            Account.check(token)
            if (it.state.pending?.token != expected) return@edit
            if (departed) it.browserReturned(owner(token),expected,System.currentTimeMillis())
            // Durable departure evidence also supports process recreation without the in-memory token.
            else it.returned(owner(token),System.currentTimeMillis())
        }
    }
    private fun browserDeparted(activity: Activity) {
        val token=launchingToken ?: return
        if(activity!==launchingActivity?.get() || activity.isChangingConfigurations) return
        departedToken=token
        scope.launch { safely { edit {it.away(token)} } }
    }
    override fun onActivityStopped(activity: Activity) = browserDeparted(activity)
    override fun onActivityResumed(activity: Activity) {
        resumed=WeakReference(activity);lifecycleRevision.value++
        val expected=departedToken ?: state.value.pending?.takeIf { it.away }?.token
        scope.launch {safely {returnToApp(expected)} }
    }
    override fun onActivityPaused(activity: Activity) {
        browserDeparted(activity)
        if(resumed?.get()===activity) resumed=null
    }
    override fun onActivityCreated(a: Activity,b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity,b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = Unit
}
