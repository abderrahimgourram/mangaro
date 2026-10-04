package eu.kanade.presentation.community

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
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp).size(96.dp)
                        .clip(RoundedCornerShape(24.dp)).background(MangaroDesignSystem.BackgroundDark)
                        .border(1.dp, MangaroDesignSystem.GoldPrimary.copy(alpha = 0.28f), RoundedCornerShape(24.dp)).padding(5.dp)) {
                        ProfileAvatar(profile.author.avatarUrl, profile.googleAvatarUrl, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.author.displayName ?: profile.author.username.orEmpty(), color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Bold)
                    profile.author.username?.let { UsernameHandle(it, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall) }
                    profile.bio?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.author.rankTitle, color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelMedium)
                        Text("Lv.${profile.author.level}", color = MangaroDesignSystem.GoldPrimary,
                            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp))
                        .background(MangaroDesignSystem.SurfaceDark).padding(vertical = 16.dp)) {
                        listOf("التعليقات" to profile.commentCount, "التقييمات" to profile.ratingCount).forEach { (label, count) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(count.toString(), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(label, color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall)
                            }
                        }
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
