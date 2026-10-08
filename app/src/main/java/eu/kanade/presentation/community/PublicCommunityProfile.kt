package eu.kanade.presentation.community

import eu.kanade.presentation.account.DeveloperBadge
import eu.kanade.presentation.account.RankIdentity
import eu.kanade.presentation.account.rankAccent
import eu.kanade.presentation.account.RankCoverAccent
import eu.kanade.presentation.account.TierAvatarFrame
import eu.kanade.presentation.account.LibraryShowcase
import eu.kanade.presentation.account.ShowcaseDisplayItem
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.account.PublicFavoriteResolver
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import mihon.domain.account.AccountActionGate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.account.ProfileAvatar
import eu.kanade.presentation.account.ProfileCoverImage
import eu.kanade.presentation.account.UsernameHandle
import eu.kanade.presentation.account.ProfileDisplayName
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import mihon.domain.community.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PublicCommunityProfileScreen(internal val userId: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        PublicCommunityProfilePanel(userId, onBack = { navigator.pop() })
    }
}

/** Public projection has no email, owner XP, cloud Library or reading data. */
@Composable
internal fun PublicCommunityProfilePanel(userId: String, onBack: () -> Unit) {
    val repository = remember { Injekt.get<CommunityRepository>() }
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val navigationGate = remember(userId) { AccountActionGate() }
    var resolving by remember(userId) { mutableStateOf(false) }
    var favoriteError by remember(userId) { mutableStateOf<String?>(null) }
    var result by remember(userId) { mutableStateOf<CommunityProfileResult?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var retry by remember(userId) { mutableIntStateOf(0) }
    LaunchedEffect(userId, retry) {
        loading = true
        try { result = repository.publicProfile(userId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { result = CommunityProfileResult.Failed(CommunityError(CommunityErrorKind.NETWORK)) }
        finally { loading = false }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") }
                Text("الملف الشخصي", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            val profile = (result as? CommunityProfileResult.Loaded)?.profile
            if (profile != null) {
                Box(Modifier.fillMaxWidth().height(228.dp)) {
                    ProfileCoverImage(profile.coverUrl, Modifier.fillMaxWidth().height(196.dp))
                    RankCoverAccent(profile.author.level, Modifier.fillMaxWidth().height(196.dp))
                    TierAvatarFrame(profile.author.level, Modifier.align(Alignment.BottomStart).padding(start = 20.dp).size(96.dp)) {
                        ProfileAvatar(profile.author.avatarUrl, profile.googleAvatarUrl, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileDisplayName(profile.author.displayName, profile.author.username, color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Bold)
                    profile.author.username?.let { UsernameHandle(it, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall) }
                    profile.bio?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodyMedium) }
                    DeveloperBadge(profile.author.role)
                    RankIdentity(profile.author.level, prominent = true)
                    eu.kanade.presentation.sigils.PublicProfileSigils(userId)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp))
                        .background(MangaroDesignSystem.SurfaceDark).padding(vertical = 16.dp)) {
                        (listOf("التعليقات" to profile.commentCount, "التقييمات" to profile.ratingCount) +
                            (if (profile.showcaseEnabled) listOf("الأعمال المعروضة" to profile.favorites.size.toLong()) else emptyList()) +
                            (profile.chaptersRead?.let { listOf("فصل مقروء" to it) } ?: emptyList())).forEach { (label, count) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(count.toString(), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(label, color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (profile.showcaseEnabled) {
                        LibraryShowcase(profile.favorites.map { ShowcaseDisplayItem(it.mangaKey,it.title,it.coverUrl,it.featured) }, onOpen = { key ->
                            val item = profile.favorites.firstOrNull { it.mangaKey == key }
                            if (item != null && navigationGate.tryStart()) {
                                resolving = true; favoriteError = null
                                scope.launch {
                                    try {
                                        val manga = PublicFavoriteResolver().resolve(item.mangaKey, item.title)
                                        if (manga != null) navigator.push(MangaScreen(manga.id))
                                        else favoriteError = "هذا العمل غير متاح على هذا الجهاز حاليًا"
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { favoriteError = "تعذّر فتح العمل — حاول مرة أخرى" }
                                    finally { resolving = false; navigationGate.finish() }
                                }
                            }
                        })
                        if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth(), color = rankAccent(profile.author.level))
                        favoriteError?.let { Text(it,style = MaterialTheme.typography.bodySmall,color = Color(0xFFB7A9C4)) }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = MangaroDesignSystem.GoldPrimary)
            if (!loading && profile == null) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (val state = result) {
                    CommunityProfileResult.NotFound -> "هذا الملف غير متاح"
                    is CommunityProfileResult.Failed -> state.error.userMessage
                    else -> "تعذّر تحميل الملف الشخصي"
                }, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { retry++ }) { Text("إعادة المحاولة") }
            }
        }
    }
}
