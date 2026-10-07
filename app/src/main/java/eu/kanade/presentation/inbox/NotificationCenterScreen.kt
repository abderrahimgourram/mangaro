package eu.kanade.presentation.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.account.AccountScreen
import eu.kanade.presentation.account.isolateUsername
import eu.kanade.presentation.account.ProfileAvatar
import eu.kanade.presentation.community.CommunityCommentsScreen
import eu.kanade.presentation.community.CommunityContext
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.domain.account.AccountSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

class NotificationCenterScreen : Screen() {
    @Composable override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = viewModel<InboxViewModel>(key = "notification-center")
        val state by model.state.collectAsState()
        val session by model.session.collectAsState()
        val work by model.work.notices.collectAsState()
        val actor = (session as? AccountSession.Authenticated)?.profile?.userId
        val privateState = state.takeIf { it.owner == actor }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var workLimit by rememberSaveable { mutableIntStateOf(20) }
        var navigating by remember { mutableStateOf(false) }
        var localMarking by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(actor, tab) { navigating = false; if (tab == 1 && actor != null) model.load() }
        BackHandler { navigator.pop() }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") }
                Text("الإشعارات", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(enabled = !navigating && !localMarking && privateState?.marking != true && (tab == 0 || actor != null), onClick = {
                    if (tab == 0) {
                        localMarking = true
                        scope.launch { try { kotlinx.coroutines.withContext(Dispatchers.IO) { model.work.markRead() } } finally { localMarking = false } }
                    } else model.markRead()
                }) { Icon(Icons.Outlined.DoneAll, "تحديد الكل كمقروء", tint = MangaroDesignSystem.GoldPrimary) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("تحديثات الأعمال", "الردود").forEachIndexed { index, title ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title) }, modifier = Modifier.weight(1f))
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (tab == 0) {
                    if (work.isEmpty()) item { QuietState("لا توجد تحديثات جديدة") }
                    items(work.take(workLimit), key = { it.id }) { notice ->
                        InboxRow(unread = notice.readAt == null, onClick = {
                            if (!navigating) {
                                navigating = true
                                scope.launch { kotlinx.coroutines.withContext(Dispatchers.IO) { model.work.markRead(notice.id) }; navigator.push(MangaScreen(notice.mangaId)); navigating = false }
                            }
                        }, leading = { MangaCover.Book(data = notice.coverData, modifier = Modifier.width(48.dp).height(68.dp)) },
                            title = notice.title, detail = if (notice.chapterIds.size > 1) "${notice.chapterIds.size} فصول جديدة" else "فصل جديد · ${notice.chapterName.orEmpty()}",
                            time = inboxTime(notice.createdAt))
                    }
                    if (work.size > workLimit) item { TextButton(onClick = { workLimit += 20 }) { Text("عرض المزيد") } }
                } else if (actor == null) {
                    item { QuietState("سجّل دخولك لمتابعة الردود على تعليقاتك") }
                    item { TextButton(onClick = { navigator.push(AccountScreen()) }) { Text("المتابعة باستخدام Google") } }
                } else {
                    val inbox = privateState ?: InboxState()
                    if (inbox.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MangaroDesignSystem.GoldPrimary) }
                    if (inbox.loaded && inbox.items.isEmpty() && inbox.error == null) item { QuietState("لا توجد ردود جديدة") }
                    inbox.error?.let { message -> item { QuietState(message); TextButton(onClick = { model.load() }) { Text("إعادة المحاولة") } } }
                    items(inbox.items, key = { it.id }) { notice ->
                        InboxRow(unread = notice.readAt == null, onClick = {
                            if (!navigating) {
                                navigating = true
                                model.markRead(notice.id)
                                // Navigation never waits for a remote read-state write.
                                navigator.push(CommunityCommentsScreen(CommunityContext(notice.target, "الردود على تعليقك"), notice.parentId))
                            }
                        }, leading = { ProfileAvatar(notice.avatar, notice.googleAvatar, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(16.dp))) },
                            title = "${isolateUsername(notice.author)} ردّ على تعليقك", detail = notice.preview,
                            time = inboxTime(runCatching { Instant.parse(notice.createdAt).toEpochMilliseconds() }.getOrDefault(0)))
                    }
                    item {
                        if (inbox.hasMore) TextButton(enabled = !inbox.loading, onClick = { model.load(more = true) }) { Text("عرض المزيد") }
                        else if (inbox.items.isNotEmpty()) TextButton(enabled = !inbox.loading, onClick = { model.load() }) { Text("تحديث الردود") }
                    }
                }
            }
        }
        }
    }
}
@Composable private fun QuietState(text: String) { Text(text, Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC4B5CE)) }
@Composable private fun InboxRow(unread: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit, title: String, detail: String, time: String) {
    val surface by animateColorAsState(if (unread) Color(0xFF22172F) else Color.Transparent, tween(160), label = "inbox-read")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(surface)
        .clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD5CADE))
            Text(time, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB3A3C0))
        }
        if (unread) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(MangaroDesignSystem.GoldPrimary))
    }
}
internal fun inboxTime(millis: Long): String = if (millis > 0) SimpleDateFormat("d MMM، HH:mm", Locale("ar")).format(Date(millis)) else ""
