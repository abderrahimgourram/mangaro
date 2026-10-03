package eu.kanade.presentation.account

import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import eu.kanade.tachiyomi.data.account.prepareAccountAvatar
import mihon.domain.account.ProfileUpdate
import mihon.domain.account.AccountProfileInput
import mihon.domain.account.AccountFoundation
import mihon.domain.account.AccountOperation
import mihon.domain.account.AccountSession
import mihon.domain.account.MangaroRanks
import mihon.domain.account.MangaroLevelProgress
import mihon.domain.account.MangaroProfile
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AccountScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        AccountPanel(onBack = { navigator.pop() })
    }
}

@Composable
fun AccountPanel(onBack: () -> Unit) {
        val account = remember { Injekt.get<AccountFoundation>() }
        val session by account.session.collectAsState()
        val authError by account.auth.error.collectAsState()
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf<String?>(null) }
        var submitting by remember { mutableStateOf(false) }
        val loading = session == AccountSession.Loading || submitting
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).safeDrawingPadding()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع", tint = Color(0xFFD6C9E0))
                    }
                    Text("الحساب", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(72.dp))
                Text("Mangaro", style = MaterialTheme.typography.headlineSmall, color = MangaroDesignSystem.GoldPrimary, fontWeight = FontWeight.Bold)
                when (val current = session) {
                    is AccountSession.Authenticated -> {
                        AccountProfileEditor(current.profile, account, submitting,
                            onAction = { action ->
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        error = when (val result = action()) {
                                            AccountOperation.Completed -> null
                                            AccountOperation.NotConfigured -> "الحساب غير متاح حاليًا"
                                            is AccountOperation.Failed -> result.message
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "تعذّر تنفيذ العملية، حاول مجددًا" }
                                    finally { submitting = false }
                                }
                            })
                    }
                    else -> {
                        Text("أنت تستخدم Mangaro كضيف", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("سجّل الدخول لحفظ مكتبتك والتفاعل مع القراء", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodyMedium)
                        Text("يمكنك القراءة والتنزيل وتنظيم مكتبتك كضيف. تبقى بياناتك محفوظة على هذا الجهاز.",
                            color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = account.auth.googleSignInAvailable && !loading,
                            onClick = {
                                scope.launch {
                                    submitting = true
                                    error = null
                                    try {
                                        when (val result = account.auth.signInWithGoogle()) {
                                            AccountOperation.NotConfigured -> error = "تسجيل الدخول غير متاح حاليًا"
                                            is AccountOperation.Failed -> error = result.message
                                            AccountOperation.Completed -> Unit // Authentication is determined only by the session stream.
                                        }
                                    } finally { submitting = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MangaroDesignSystem.GoldPrimary, contentColor = MangaroDesignSystem.BackgroundDark,
                                disabledContainerColor = MangaroDesignSystem.SurfaceHigh, disabledContentColor = Color(0xFFB7A9C4),
                            ),
                        ) {
                            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("المتابعة باستخدام Google")
                        }
                        if (!account.auth.googleSignInAvailable) Text("تسجيل الدخول غير متاح حاليًا", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                    }
                }
                (error ?: authError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
}

@Composable
private fun AccountProfileEditor(
    profile: MangaroProfile,
    account: AccountFoundation,
    submitting: Boolean,
    onAction: (suspend () -> AccountOperation) -> Unit,
) {
    val context = LocalContext.current
    var username by remember(profile.userId, profile.username) { mutableStateOf(profile.username.orEmpty()) }
    var displayName by remember(profile.userId, profile.displayName) { mutableStateOf(profile.displayName.orEmpty()) }
    val update = remember(username, displayName) { ProfileUpdate(displayName, username) }
    val validation = remember(update) { AccountProfileInput.error(update) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAction {
            val webp = try { prepareAccountAvatar(context, uri) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@onAction AccountOperation.Failed("اختر صورة JPEG أو PNG أو WebP لا تتجاوز 2 ميغابايت") }
            account.auth.uploadAvatar(webp)
        }
    }
    AccountAvatar(profile, Modifier.size(80.dp))
    Text(profile.displayName ?: profile.username.orEmpty(), color = Color.White)
    profile.username?.let { UsernameHandle(it, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodyLarge) }
    // Email is visible only in this owner's account area, never public comments/profiles.
    profile.email?.let { Text(it, color = Color(0xFFB7A9C4)) }
    Text("المستوى ${profile.level} · ${profile.rankTitle}", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
    Text("${profile.xp} XP", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
    if (profile.level == MangaroRanks.MAX_LEVEL) {
        Text("المستوى الأقصى", color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.labelSmall)
    } else {
        val earned = MangaroLevelProgress.earned(profile)
        val required = MangaroLevelProgress.required(profile.level)
        LinearProgressIndicator(progress = { (earned.toFloat() / required).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp), color = MangaroDesignSystem.GoldPrimary,
            trackColor = MangaroDesignSystem.SurfaceHigh)
        Text("$earned / $required XP", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
    }
    if (profile.username == null) Text("أكمل ملفك باختيار اسم مستخدم فريد. يمكنك مواصلة القراءة كالمعتاد.",
        color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(displayName, { displayName = it }, label = { Text("اسم العرض") },
        enabled = !submitting, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
    OutlinedTextField(username, { username = it }, label = { Text("اسم المستخدم") },
        supportingText = { Text("3–24 حرفًا إنجليزيًا أو رقمًا أو شرطة سفلية") },
        enabled = !submitting, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
    if (validation != null && username.isNotEmpty()) Text(validation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Button(enabled = !submitting && validation == null, onClick = { onAction { account.auth.updateProfile(update) } },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MangaroDesignSystem.GoldPrimary, contentColor = MangaroDesignSystem.BackgroundDark)) {
        if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("حفظ الملف الشخصي")
    }
    Row {
        TextButton(enabled = !submitting, onClick = { avatarPicker.launch("image/*") }) { Text("تغيير الصورة") }
        TextButton(enabled = !submitting, onClick = { onAction { account.auth.removeAvatar() } }) { Text("إزالة الصورة") }
    }
    TextButton(enabled = !submitting, onClick = { onAction { account.auth.signOut(); AccountOperation.Completed } }) { Text("تسجيل الخروج") }
}

@Composable
fun AccountDrawerArea(session: AccountSession, onLogin: () -> Unit, onProfile: ((MangaroProfile) -> Unit)? = null) {
    val profile = (session as? AccountSession.Authenticated)?.profile
    Surface(shape = RoundedCornerShape(16.dp), color = MangaroDesignSystem.SurfaceHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(profile, Modifier.size(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(profile?.displayName ?: profile?.username ?: "MANGARO", color = MangaroDesignSystem.GoldPrimary,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    profile?.let { Text("${it.rankTitle} · المستوى ${it.level}", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall) }
                    val handle = profile?.username
                    if (handle != null) UsernameHandle(handle, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                    else Text(if (profile != null) "الملف الشخصي" else "أنت تستخدم Mangaro كضيف",
                        color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                }
            }
            when (session) {
                is AccountSession.Authenticated -> TextButton(enabled = onProfile != null, onClick = { onProfile?.invoke(session.profile) }) {
                    Text("الملف الشخصي", color = MangaroDesignSystem.GoldPrimary)
                }
                AccountSession.Loading -> CircularProgressIndicator(Modifier.size(20.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
                AccountSession.Guest -> {
                    Text("سجّل الدخول لحفظ مكتبتك والتفاعل مع القراء", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onLogin) { Text("تسجيل الدخول", color = MangaroDesignSystem.GoldPrimary) }
                }
            }
        }
    }
}

/** Failed custom/Google images fall back without breaking session/profile state. Uses existing Coil. */
@Composable
private fun AccountAvatar(profile: MangaroProfile?, modifier: Modifier) {
    val urls = remember(profile?.avatarUrl, profile?.googleAvatarUrl) {
        listOfNotNull(profile?.avatarUrl, profile?.googleAvatarUrl).distinct()
    }
    var index by remember(urls) { mutableStateOf(0) }
    if (index < urls.size) AsyncImage(urls[index], null, modifier = modifier,
        onError = { index += 1 })
    else Image(painterResource(R.drawable.ic_splash_logo), null, modifier)
}

/** One reusable account-required prompt, delegating authentication to the existing account UI. */
@Composable
fun AccountRequiredPrompt(onDismiss: () -> Unit, onAccount: () -> Unit, profileIncomplete: Boolean = false) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.SurfaceDark,
        title = { Text(if (profileIncomplete) "أكمل ملفك الشخصي للمشاركة" else "سجّل دخولك للمشاركة") },
        text = { Text("التعليقات والتقييمات ونظام المستوى متاحة لأعضاء Mangaro.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onAccount() }) { Text(if (profileIncomplete) "إكمال الملف الشخصي" else "المتابعة باستخدام Google") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ليس الآن") } })
}
