package eu.kanade.presentation.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.manga.interactor.GetLibraryManga
import mihon.domain.account.ProfileStatistics
import eu.kanade.tachiyomi.data.account.prepareAccountCover
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import mihon.domain.account.AccountActionGate
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
        val actionGate = remember { AccountActionGate() }
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
                    Text(if (session is AccountSession.Authenticated) "الملف الشخصي" else "الحساب", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                }
                if (session !is AccountSession.Authenticated) {
                    Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(72.dp))
                    Text("Mangaro", style = MaterialTheme.typography.headlineSmall, color = MangaroDesignSystem.GoldPrimary, fontWeight = FontWeight.Bold)
                }
                when (val current = session) {
                    is AccountSession.Authenticated -> {
                        AccountProfileEditor(current.profile, account, submitting, error ?: authError,
                            onAction = { action ->
                                if (actionGate.tryStart()) {
                                    submitting = true
                                    scope.launch {
                                        error = null
                                        try {
                                            error = when (val result = action()) {
                                                AccountOperation.Completed -> null
                                                AccountOperation.NotConfigured -> "الحساب غير متاح حاليًا"
                                                is AccountOperation.Failed -> result.message
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { error = "تعذّر تنفيذ العملية، حاول مجددًا" }
                                        finally { submitting = false; actionGate.finish() }
                                    }
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
                                if (actionGate.tryStart()) {
                                    submitting = true
                                    scope.launch {
                                        error = null
                                        try {
                                            when (val result = account.auth.signInWithGoogle()) {
                                                AccountOperation.NotConfigured -> error = "تسجيل الدخول غير متاح حاليًا"
                                                is AccountOperation.Failed -> error = result.message
                                                AccountOperation.Completed -> Unit // Authentication is determined only by the session stream.
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { error = "تعذّر تسجيل الدخول، حاول مجددًا" }
                                        finally { submitting = false; actionGate.finish() }
                                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountProfileEditor(
    profile: MangaroProfile,
    account: AccountFoundation,
    submitting: Boolean,
    errorMessage: String?,
    onAction: (suspend () -> AccountOperation) -> Unit,
) {
    val context = LocalContext.current
    var levelNotice by remember(profile.userId) { mutableStateOf<String?>(null) }
    LaunchedEffect(profile.userId, profile.level) {
        levelNotice = null
        val milestone = account.auth.consumeRankMilestone(profile.userId)
        if (milestone != null) {
            levelNotice = "وصلت إلى المستوى ${milestone.level}\nتم فتح هوية رتبة جديدة" +
                (if (milestone.addedSlots > 0) "\nتم فتح ${milestone.addedSlots} خانات إضافية في مكتبتك العامة" else "")
            kotlinx.coroutines.delay(4500)
            levelNotice = null
        }
    }
    var username by rememberSaveable(profile.userId, profile.username) { mutableStateOf(profile.username.orEmpty()) }
    var displayName by rememberSaveable(profile.userId, profile.displayName) { mutableStateOf(profile.displayName.orEmpty()) }
    var bio by rememberSaveable(profile.userId, profile.bio) { mutableStateOf(profile.bio.orEmpty()) }
    val update = remember(username, displayName, bio) { ProfileUpdate(displayName, username, bio) }
    val validation = remember(update) { AccountProfileInput.error(update) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAction {
            val webp = try { prepareAccountAvatar(context, uri) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@onAction AccountOperation.Failed("اختر صورة JPEG أو PNG أو WebP لا تتجاوز 2 ميغابايت") }
            account.auth.uploadAvatar(webp)
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAction {
            val webp = try { prepareAccountCover(context, uri) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@onAction AccountOperation.Failed("اختر غلاف JPEG أو PNG أو WebP لا يتجاوز 4 ميغابايت") }
            account.auth.uploadCover(webp)
        }
    }
    var available by remember(profile.userId, username) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(profile.userId, username) {
        if (AccountProfileInput.username(username) != profile.username && AccountProfileInput.username(username).matches(Regex("[a-z0-9_]{3,24}"))) {
            kotlinx.coroutines.delay(350)
            available = account.auth.usernameAvailable(username)
        }
    }
    var editing by rememberSaveable(profile.userId) { mutableStateOf(false) }
    val secondary = Color(0xFFB7A9C4)
    val muted = Color(0xFF8F819E)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MangaroDesignSystem.SurfaceDark)) {
        Box(Modifier.fillMaxWidth().height(216.dp)) {
            ProfileCover(profile, Modifier.fillMaxWidth().height(180.dp))
            RankCoverAccent(profile.level, Modifier.fillMaxWidth().height(180.dp))
            TierAvatarFrame(profile.level, Modifier.align(Alignment.BottomStart).padding(start = 16.dp).size(100.dp)) {
                AccountAvatar(profile, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MangaroDesignSystem.SurfaceHigh))
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(profile.displayName ?: profile.username.orEmpty(), color = Color.White,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    profile.username?.let { UsernameHandle(it, color = secondary, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(enabled = !submitting, onClick = { editing = true },
                    contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = secondary, containerColor = MangaroDesignSystem.SurfaceHigh)) {
                    Text("تعديل الملف", style = MaterialTheme.typography.labelMedium)
                }
            }
            DeveloperBadge(profile.role)
            RankIdentity(profile.level, prominent = true)
            levelNotice?.let { message ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(mihon.domain.account.RankVisuals.resolve(profile.level).surface))
                    .padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    RankEmblem(profile.level,Modifier.size(28.dp))
                    Text(message,color=rankAccent(profile.level),style=MaterialTheme.typography.labelMedium)
                }
            }
            profile.bio?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = secondary.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    AccountProfileStatistics(profile.userId, account)
    ProfileShowcaseControls(profile, account)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MangaroDesignSystem.SurfaceDark) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("الخبرة", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("Lv.${profile.level}", style = MaterialTheme.typography.labelMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr), color = rankAccent(profile.level))
            }
            if (profile.level == MangaroRanks.MAX_LEVEL) {
                Text("المستوى الأقصى", color = secondary, style = MaterialTheme.typography.bodySmall)
            } else {
                val earned = MangaroLevelProgress.earned(profile)
                val required = MangaroLevelProgress.required(profile.level)
                LinearProgressIndicator(progress = { (earned.toFloat() / required).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = rankAccent(profile.level),
                    trackColor = MangaroDesignSystem.SurfaceHigh)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إلى المستوى التالي", color = secondary, style = MaterialTheme.typography.labelSmall)
                    Text("$earned / $required XP", color = rankAccent(profile.level), style = MaterialTheme.typography.labelMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                }
            }
            Text("إجمالي الخبرة: ${profile.xp} XP", color = muted, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (editing) {
        ModalBottomSheet(onDismissRequest = { editing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MangaroDesignSystem.SurfaceDark) {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("تعديل الملف", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (profile.username == null) Text("أكمل ملفك باختيار اسم مستخدم فريد. يمكنك مواصلة القراءة كالمعتاد.",
                    color = secondary, style = MaterialTheme.typography.bodySmall)
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.65f),
                    unfocusedBorderColor = Color(0xFF3C2C49), focusedContainerColor = MangaroDesignSystem.SurfaceDark,
                    unfocusedContainerColor = MangaroDesignSystem.SurfaceDark, focusedLabelColor = secondary,
                    unfocusedLabelColor = muted, cursorColor = MangaroDesignSystem.GoldPrimary)
                OutlinedTextField(displayName, { displayName = it }, label = { Text("اسم العرض") }, colors = fieldColors,
                    enabled = !submitting, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(username, { username = it }, label = { Text("اسم المستخدم") }, colors = fieldColors,
                    supportingText = { Text("3–24 حرفًا إنجليزيًا أو رقمًا أو شرطة سفلية") },
                    enabled = !submitting, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                available?.let { Text(if (it) "اسم المستخدم متاح" else "اسم المستخدم غير متاح", style = MaterialTheme.typography.labelSmall,
                    color = if (it) secondary else MaterialTheme.colorScheme.error) }
                OutlinedTextField(bio, { bio = it }, label = { Text("نبذة عنك") }, colors = fieldColors,
                    supportingText = { Text("${bio.trim().codePointCount(0, bio.trim().length)} / 160") },
                    enabled = !submitting, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (validation != null && username.isNotEmpty()) Text(validation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(enabled = !submitting && validation == null && available != false, onClick = { onAction { account.auth.updateProfile(update).also { if (it == AccountOperation.Completed) editing = false } } },
                        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MangaroDesignSystem.GoldPrimary, contentColor = MangaroDesignSystem.BackgroundDark)) {
                        // Fixed content width keeps the action balanced while its request is in flight.
                        Box(Modifier.size(width = 88.dp, height = 24.dp), contentAlignment = Alignment.Center) {
                            if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("حفظ التعديل")
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF2C2035))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(profile, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
                    TextButton(enabled = !submitting, onClick = { avatarPicker.launch("image/*") }) { Text("تغيير الصورة", color = secondary) }
                    if (profile.avatarUrl != null && profile.avatarUrl != profile.googleAvatarUrl)
                        TextButton(enabled = !submitting, onClick = { onAction { account.auth.removeAvatar() } }) { Text("إزالة الصورة", color = muted) }
                }
                Text("الغلاف", color = secondary, style = MaterialTheme.typography.titleSmall)
                ProfileCover(profile, Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp)))
                Row {
                    TextButton(enabled = !submitting, onClick = { coverPicker.launch("image/*") }) { Text("تغيير الغلاف", color = secondary) }
                    if (profile.coverUrl != null) TextButton(enabled = !submitting, onClick = { onAction { account.auth.removeCover() } }) { Text("إزالة الغلاف", color = muted) }
                }
            }
        }
    }
    CloudSyncControls(profile.userId, account.cloudSync)
    HorizontalDivider(color = Color(0xFF2C2035))
    // Email remains confined to the owner's Account screen, below the primary profile content.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("معلومات الحساب", color = secondary, style = MaterialTheme.typography.titleSmall)
        profile.email?.let { Text(it, color = muted, style = MaterialTheme.typography.bodySmall) }
    }
    TextButton(enabled = !submitting, onClick = { onAction { account.auth.signOut(); AccountOperation.Completed } },
        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC69BA7))) { Text("تسجيل الخروج") }

}

@Composable
private fun ProfileCover(profile: MangaroProfile?, modifier: Modifier) {
    ProfileCoverImage(profile?.coverUrl, modifier)
}

@Composable
internal fun ProfileCoverImage(coverUrl: String?, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        // Decorative local artwork only; no user cover or remote image request.
        Canvas(Modifier.matchParentSize().clipToBounds().background(Brush.horizontalGradient(
            listOf(MangaroDesignSystem.SurfaceDark, Color(0xFF30203E), MangaroDesignSystem.SurfaceHigh)))) {
            drawCircle(Color(0xFF49315C).copy(alpha = 0.24f), radius = size.width * 0.42f,
                center = Offset(size.width * 0.8f, -size.height * 0.3f))
            drawCircle(MangaroDesignSystem.BackgroundDark.copy(alpha = 0.28f), radius = size.width * 0.36f,
                center = Offset(size.width * 0.15f, size.height * 1.25f))
            // Quiet panel facets evoke manga page composition without borrowed artwork.
            drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.06f, size.height * 0.12f)
                lineTo(size.width * 0.3f, size.height * 0.05f)
                lineTo(size.width * 0.5f, size.height * 0.92f)
                lineTo(size.width * 0.2f, size.height * 1.12f)
                close()
            }, Color(0xFF654974).copy(alpha = 0.13f))
            drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.63f, -size.height * 0.1f)
                lineTo(size.width * 0.97f, size.height * 0.13f)
                lineTo(size.width * 0.84f, size.height * 0.88f)
                lineTo(size.width * 0.54f, size.height * 0.7f)
                close()
            }, MangaroDesignSystem.BackgroundDark.copy(alpha = 0.18f))
            drawLine(MangaroDesignSystem.GoldPrimary.copy(alpha = 0.1f),
                Offset(size.width * 0.32f, 0f), Offset(size.width * 0.62f, size.height), strokeWidth = 1.dp.toPx())
            drawLine(Color(0xFF89709F).copy(alpha = 0.12f),
                Offset(size.width * 0.36f, 0f), Offset(size.width * 0.66f, size.height), strokeWidth = 1.dp.toPx())
        }
        coverUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to MangaroDesignSystem.SurfaceDark.copy(alpha = 0.2f),
            1f to MangaroDesignSystem.SurfaceDark.copy(alpha = 0.96f))))
    }
}

@Composable
private fun AccountProfileStatistics(userId: String, account: AccountFoundation) {
    val library = remember { Injekt.get<GetLibraryManga>() }
    val libraryCount by remember(library) { library.subscribe().map { rows -> rows.map { it.id }.distinct().size.toLong() }.distinctUntilChanged() }
        .collectAsState(initial = null)
    var counts by remember(userId) { mutableStateOf<ProfileStatistics?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var retry by remember(userId) { mutableStateOf(0) }
    LaunchedEffect(userId, retry) {
        loading = true
        try { counts = account.auth.profileStatistics() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { counts = null }
        finally { loading = false }
    }
    Column {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MangaroDesignSystem.SurfaceDark) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple("في المكتبة", libraryCount, Icons.Outlined.Bookmarks),
                    Triple("التعليقات", counts?.comments, Icons.Outlined.ChatBubbleOutline),
                    Triple("التقييمات", counts?.ratings, Icons.Outlined.StarOutline),
                ).forEachIndexed { index, (label, value, icon) ->
                    if (index > 0) Box(Modifier.size(width = 1.dp, height = 42.dp).background(MangaroDesignSystem.SurfaceHigh))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF8F819E))
                        Text(value?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB7A9C4))
                    }
                }
            }
        }
        if (!loading && counts == null) TextButton(onClick = { retry++ }) { Text("إعادة تحميل الإحصاءات", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun AccountDrawerArea(session: AccountSession, onLogin: () -> Unit, onProfile: ((MangaroProfile) -> Unit)? = null) {
    val profile = (session as? AccountSession.Authenticated)?.profile
    val secondary = Color(0xFFB7A9C4)
    val muted = Color(0xFF8F819E)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF2C1E38), MangaroDesignSystem.SurfaceDark)))
        .border(1.dp, Color(0xFF493253).copy(alpha = 0.45f), RoundedCornerShape(24.dp))) {
        Box(Modifier.fillMaxWidth().height(162.dp)) {
            ProfileCover(profile, Modifier.fillMaxWidth().height(140.dp))
            Box(Modifier.align(Alignment.BottomStart).padding(start = 4.dp).size(96.dp)
                .background(Brush.radialGradient(listOf(MangaroDesignSystem.GoldPrimary.copy(alpha = 0.09f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp).size(76.dp)
                .clip(RoundedCornerShape(24.dp)).background(MangaroDesignSystem.SurfaceDark)
                .border(1.dp, MangaroDesignSystem.GoldPrimary.copy(alpha = 0.38f), RoundedCornerShape(24.dp)).padding(4.dp)) {
                AccountAvatar(profile, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MangaroDesignSystem.SurfaceHigh))
            }
            if (session is AccountSession.Authenticated) {
                TextButton(enabled = onProfile != null, onClick = { onProfile?.invoke(session.profile) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 2.dp),
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MangaroDesignSystem.GoldPrimary,
                        containerColor = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.07f))) {
                    Text("الملف الشخصي", style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(start = 5.dp).size(14.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (session) {
                is AccountSession.Authenticated -> {
                    Text(profile?.displayName ?: profile?.username.orEmpty(), color = Color.White,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    profile?.username?.let { UsernameHandle(it, color = secondary, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(session.profile.rankTitle, color = secondary, style = MaterialTheme.typography.labelSmall)
                        Text("Lv.${session.profile.level}", color = MangaroDesignSystem.GoldPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MangaroDesignSystem.GoldPrimary.copy(alpha = 0.08f)).padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                    }
                    profile?.bio?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val account = remember { Injekt.get<AccountFoundation>() }
                    val sync by remember(session.profile.userId, account) { account.cloudSync.observe(session.profile.userId) }.collectAsState()
                    if (sync.loaded && sync.enabled) {
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFB7A9C4).copy(alpha = 0.06f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(when {
                                sync.running -> Icons.Outlined.Sync
                                sync.error != null -> Icons.Outlined.ErrorOutline
                                sync.pending > 0 -> Icons.Outlined.Schedule
                                else -> Icons.Outlined.CheckCircleOutline
                            }, null, Modifier.size(13.dp), tint = muted)
                            Text(when {
                                sync.running -> "تتم المزامنة..."
                                sync.error != null -> "تعذر المزامنة"
                                sync.pending > 0 -> "بانتظار المزامنة"
                                else -> "المزامنة مفعّلة"
                            }, color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                AccountSession.Loading -> CircularProgressIndicator(Modifier.size(20.dp), color = MangaroDesignSystem.GoldPrimary, strokeWidth = 2.dp)
                AccountSession.Guest -> {
                    Text("أنت تستخدم Mangaro كضيف", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("سجّل الدخول لحفظ مكتبتك والتفاعل مع القراء", color = secondary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onLogin, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MangaroDesignSystem.GoldPrimary, containerColor = MangaroDesignSystem.SurfaceHigh)) {
                        Text("المتابعة باستخدام Google", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Failed custom/Google images fall back without breaking session/profile state. Uses existing Coil. */
@Composable
private fun AccountAvatar(profile: MangaroProfile?, modifier: Modifier) {
    ProfileAvatar(profile?.avatarUrl, profile?.googleAvatarUrl, modifier)
}

@Composable
internal fun ProfileAvatar(avatarUrl: String?, googleAvatarUrl: String? = null, modifier: Modifier) {
    val urls = remember(avatarUrl, googleAvatarUrl) {
        listOfNotNull(avatarUrl, googleAvatarUrl).distinct()
    }
    var index by remember(urls) { mutableStateOf(0) }
    androidx.compose.foundation.layout.Box(modifier) {
        Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.matchParentSize())
        if (index < urls.size) {
            val loadingIndex = index
            AsyncImage(urls[loadingIndex], null, modifier = Modifier.matchParentSize(),
                onError = { if (index == loadingIndex) index = loadingIndex + 1 })
        }
    }
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
