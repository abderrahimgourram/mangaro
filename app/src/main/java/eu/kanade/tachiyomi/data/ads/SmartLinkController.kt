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
    private val destroyed=kotlinx.coroutines.flow.MutableSharedFlow<Activity>(extraBufferCapacity=2)
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
        if(InMobiAds.get(app).privacyChoice()==AdPrivacyChoice.DISABLED) return "الإعلانات مرفوضة في اختيارات الخصوصية؛ يمكنك الرجوع أو إعادة المحاولة"
        val navigation=Intent(Intent.ACTION_VIEW,Uri.parse(SmartLinkConfiguration.URL)).addCategory(Intent.CATEGORY_BROWSABLE)
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
        launchingActivity=WeakReference(activity);launchingToken=id
        try {
            withContext(Dispatchers.Main.immediate) { Account.check(token);activity.startActivity(navigation) }
            edit { Account.check(token);it.accepted(id,System.currentTimeMillis()) }
            return null
        } catch(c: CancellationException) { withContext(NonCancellable) { edit {it.cancel(id)} };throw c }
        catch(_:Exception) { edit {it.cancel(id)};return "تعذّر فتح الرابط الإعلاني؛ لم تُحتسب خطوة ويمكنك إعادة المحاولة" }
    }
    suspend fun pendingReader(kind: String, work: String, token: Account.Token): ReaderAdTransition? {
        Account.check(token)
        return edit { it.state.readerTransitions[adHash(owner(token),kind)]?.takeIf { row -> row.work==work } }
    }
    suspend fun readerApplied(kind: String, work: String, destination: String, token: Account.Token) {
        Account.check(token); edit { Account.check(token);it.applied(owner(token),kind,work,destination) }
    }
    suspend fun readerEntry(kind: String, work: String, destination: String, payload: String?, chapterKey: String, previouslyRead: Boolean, token: Account.Token): Boolean {
        if(!BuildConfig.SMARTLINK_V3_ENABLED || InMobiAds.get(app).privacyChoice()==AdPrivacyChoice.DISABLED) return true
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
        if(InMobiAds.get(app).privacyChoice()==AdPrivacyChoice.DISABLED) return true
        val transition=edit { Account.check(token);it.transition(owner(token),kind,work,from,destination,payload) } ?: return true
        if(transition.satisfied) return true
        while(true) {
            Account.check(token)
            val activity=withTimeoutOrNull(2_000) { lifecycleRevision.map { resumed?.get() }.filterNotNull().first { !it.isFinishing && !it.isDestroyed } } ?: return false
            showReaderGate(activity,transition,destination,kind,token)?.let { return it }
            // Configuration recreation: keep the same durable intent and attach to the next Activity.
            yield()
        }
    }
    private suspend fun showReaderGate(activity: Activity, transition: ReaderAdTransition, destination: String, kind: String, token: Account.Token): Boolean? =
        withContext(Dispatchers.Main.immediate) {
            coroutineScope {
                val result=CompletableDeferred<Boolean?>()
                val purple=android.graphics.Color.rgb(27,20,35)
                val gold=android.graphics.Color.rgb(224,184,97)
                val density=activity.resources.displayMetrics.density
                fun dp(value: Int)=(value*density).toInt()
                val content=android.widget.LinearLayout(activity).apply {
                    orientation=android.widget.LinearLayout.VERTICAL
                    layoutDirection=android.view.View.LAYOUT_DIRECTION_RTL
                    gravity=android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(dp(24),dp(32),dp(24),dp(32))
                }
                fun label(value: String)=android.widget.TextView(activity).apply {
                    text=value;setTextColor(android.graphics.Color.rgb(235,222,244));textSize=16f
                    gravity=android.view.Gravity.CENTER;setPadding(0,dp(8),0,dp(8))
                    content.addView(this,android.widget.LinearLayout.LayoutParams(-1,-2))
                }
                label("متابعة القراءة").apply {textSize=28f;setTextColor(gold)}
                label("افتح إعلانًا واحدًا للمتابعة")
                label("إعلان خارجي · Adsterra · asiafilm.org").apply {textSize=12f}
                val message=label("").apply {setTextColor(android.graphics.Color.rgb(255,180,180))}
                val button=android.widget.Button(activity).apply {
                    text="شاهد إعلان للإكمال";isAllCaps=false;minHeight=dp(52)
                    backgroundTintList=android.content.res.ColorStateList.valueOf(gold)
                    setTextColor(purple)
                    content.addView(this,android.widget.LinearLayout.LayoutParams(-1,-2))
                }
                // A full-window gate, not a chapter-end card or a floating alert.
                // Keep the same Activity so the validated external launch/return proof is unchanged.
                val screen=android.widget.ScrollView(activity).apply {
                    isFillViewport=true;setBackgroundColor(purple)
                    addView(android.widget.FrameLayout(activity).apply {
                        addView(content,android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.CENTER))
                    },android.widget.FrameLayout.LayoutParams(-1,-1))
                }
                val dialog=android.app.Dialog(activity,android.R.style.Theme_Material_NoActionBar).apply {
                    requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                    setContentView(screen)
                    setCancelable(true);setCanceledOnTouchOutside(false)
                    window?.apply {
                        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(purple))
                        statusBarColor=purple;navigationBarColor=purple
                        decorView.layoutDirection=android.view.View.LAYOUT_DIRECTION_RTL
                    }
                }
                dialog.setOnCancelListener { abandon(transition.id,token);result.complete(false) }
                button.setOnClickListener {
                    if(button.isEnabled) launch {
                        button.isEnabled=false
                        try { message.text=this@SmartLinkController.launch(activity,transition.id,token).orEmpty() }
                        catch(cancelled:CancellationException) {throw cancelled}
                        catch(_:Exception) {message.text="تعذّر فتح الرابط؛ لم تُحتسب خطوة. أعد المحاولة أو استخدم الرجوع"}
                        finally {button.isEnabled=true}
                    }
                }
                val proof=launch {
                    state.first { it.readerTransitions[adHash(owner(token),kind)]?.let { row -> row.id==transition.id && row.destination==destination && row.satisfied }==true }
                    result.complete(true)
                }
                val ownership=launch { Account.changes.first { !it.ready || it.token!=token };result.complete(false) }
                val destruction=launch { destroyed.first { it===activity };result.complete(null) }
                try {
                    dialog.show()
                    dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    result.await().also { if(it==true) Account.check(token) }
                }
                finally { proof.cancel();ownership.cancel();destruction.cancel();dialog.dismiss() }
            }
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
    override fun onActivityResumed(activity: Activity) { resumed=WeakReference(activity);lifecycleRevision.value++;scope.launch {safely {returnToApp()} } }
    override fun onActivityPaused(activity: Activity) { if(resumed?.get()===activity) resumed=null }
    override fun onActivityCreated(a: Activity,b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity,b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) { destroyed.tryEmit(a) }
}
