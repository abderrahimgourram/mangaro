package eu.kanade.tachiyomi.data.ads

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.LocalAccountScope as Account

/** An inter-chapter page in the Activity back stack. It never has a chapter ID or page number. */
class ReaderAdPageActivity : BaseActivity() {
    companion object {
        private const val OWNER = "ad.owner"
        private const val KIND = "ad.kind"
        private const val BOUNDARY = "ad.boundary"
        private const val SESSION = "ad.session"
        internal fun intent(context: Context, key: ReaderAdPageHost.Key, session: String) =
            Intent(context, ReaderAdPageActivity::class.java)
                .putExtra(OWNER, key.owner).putExtra(KIND, key.kind)
                .putExtra(BOUNDARY, key.boundary).putExtra(SESSION, session)
    }

    private val controller by lazy { SmartLinkController.get(this) }
    private var pageKey: ReaderAdPageHost.Key? = null
    private var session: String? = null
    private var connectivity: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = intent.getStringExtra(OWNER)
        val kind = intent.getStringExtra(KIND)
        val boundary = intent.getStringExtra(BOUNDARY)
        val sessionId = intent.getStringExtra(SESSION)
        if (!BuildConfig.SMARTLINK_V3_ENABLED || owner.isNullOrBlank() ||
            kind !in setOf("novel", "manga") || boundary.isNullOrBlank() || sessionId.isNullOrBlank()
        ) { finish(); return }
        val key = ReaderAdPageHost.Key(owner, kind!!, boundary)
        if (!controller.attachReaderPage(key, sessionId)) { finish(); return }
        pageKey = key
        session = sessionId
        supportActionBar?.hide()

        val purple = Color.rgb(27, 20, 35)
        val gold = Color.rgb(224, 184, 97)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        fun label(value: String) = TextView(this).apply {
            text = value; textSize = 16f; setTextColor(Color.rgb(235, 222, 244))
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label("متابعة القراءة").apply { textSize = 28f; setTextColor(gold) }
        label("افتح إعلانًا واحدًا للمتابعة")
        label("إعلان خارجي · Adsterra · asiafilm.org").textSize = 12f
        val message = label("").apply { setTextColor(Color.rgb(255, 180, 180)) }
        val button = Button(this).apply {
            text = "شاهد إعلان للإكمال"; isAllCaps = false; minHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(gold); setTextColor(purple)
            isEnabled = false
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true; fitsSystemWindows = true; setBackgroundColor(purple)
            addView(FrameLayout(this@ReaderAdPageActivity).apply {
                addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }, FrameLayout.LayoutParams(-1, -1))
        })
        // Offline reading is exempt, including loss of connectivity while this page is visible.
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            private fun checkConnection() = runOnUiThread { if (!controller.online()) finish() }
            override fun onLost(network: android.net.Network) = checkConnection()
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = checkConnection()
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(callback); networkCallback = callback }

        lifecycleScope.launch {
            val account = Account.changes.first { it.ready }.token
            if (SmartLinkController.owner(account) != owner) { finish(); return@launch }
            launch {
                Account.changes.first { !it.ready || it.token != account }
                finish()
            }
            val transition = try { controller.currentReaderPage(key, account) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                message.text = "تعذّر استعادة الفاصل؛ ارجع وأعد المحاولة"
                return@launch
            }
            if (transition == null) { finish(); return@launch }
            button.isEnabled = !transition.satisfied
            launch {
                controller.state.first { state ->
                    state.readerTransitions[adHash(owner, kind)]?.let { it.id != boundary || it.satisfied } != false
                }
                finish()
            }
            button.setOnClickListener {
                if (button.isEnabled) lifecycleScope.launch {
                    button.isEnabled = false
                    try { message.text = controller.launch(this@ReaderAdPageActivity, boundary, account).orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message.text = "تعذّر فتح الرابط؛ أعد المحاولة" }
                    finally { button.isEnabled = true }
                }
            }
            if (transition.satisfied) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pageKey != null && !controller.online()) finish()
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        if (isFinishing) pageKey?.let { key ->
            session?.let { controller.closeReaderPage(key, it) }
        }
        super.onDestroy()
    }
}
