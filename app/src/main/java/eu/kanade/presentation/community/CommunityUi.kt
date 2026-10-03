package eu.kanade.presentation.community

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.account.AccountRequiredPrompt
import eu.kanade.presentation.account.AccountPanel
import eu.kanade.presentation.account.AccountScreen
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import mihon.domain.account.*
import mihon.domain.community.*
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterIdentity
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable

/** The same identity mapping is used by Details and Reader. No source name is rendered. */
data class CommunityContext(val target: CommunityTarget, val title: String) : Serializable
fun communityContextFor(manga: Manga, chapter: Chapter? = null): CommunityContext = CommunityContext(
    target = CommunityTarget(
        targetType = if (chapter == null) CommunityTargetType.MANGA else CommunityTargetType.CHAPTER,
        mangaKey = CommunityMangaKey(manga.source, manga.url),
        chapterKey = chapter?.let {
            val remote = ChapterIdentity.remoteIds(it, manga.source).sorted().firstOrNull()
            CommunityChapterKey(if (remote == null) "url:${it.url}" else "id:$remote")
        },
    ),
    title = chapter?.name ?: manga.title,
)

class CommunityCommentsScreen(private val context: CommunityContext) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Column(Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") }
                Text(if (context.target.targetType == CommunityTargetType.CHAPTER) "تعليقات الفصل" else "تعليقات العمل",
                    style = MaterialTheme.typography.titleLarge)
            }
            CommunityContent(context, expanded = true, onAccount = { navigator.push(AccountScreen()) })
        }
    }
}

@Composable
fun MangaCommunitySection(manga: Manga) {
    val navigator = LocalNavigator.currentOrThrow
    val context = remember(manga.source, manga.url, manga.title) { communityContextFor(manga) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp), color = MangaroDesignSystem.SurfaceDark) {
        CommunityContent(context, expanded = false,
            onAccount = { navigator.push(AccountScreen()) },
            onAllComments = { navigator.push(CommunityCommentsScreen(context)) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderCommunitySheet(context: CommunityContext, onDismiss: () -> Unit) {
    var accountVisible by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.BackgroundDark) {
        if (accountVisible) Box(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            AccountPanel(onBack = { accountVisible = false })
        } else Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            Text("تعليقات الفصل", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            CommunityContent(context, expanded = true, onAccount = { accountVisible = true })
        }
    }
}

@Composable
private fun CommunityContent(
    context: CommunityContext, expanded: Boolean, onAccount: () -> Unit, onAllComments: (() -> Unit)? = null,
) {
    val repository = remember { Injekt.get<CommunityRepository>() }
    val account = remember { Injekt.get<AccountFoundation>() }
    val snapshot by remember(context.target) { repository.observe(context.target) }.collectAsState()
    val session by account.session.collectAsState()
    val scope = rememberCoroutineScope()
    var loginPrompt by remember { mutableStateOf(false) }
    var ratingVisible by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reply by remember(context.target) { mutableStateOf<CommunityComment?>(null) }
    var editing by remember(context.target) { mutableStateOf<CommunityComment?>(null) }
    fun gated(feature: AccountFeature, action: () -> Unit) {
        when (account.featureGate.request(feature)) {
            is AccountAccess.LoginRequired -> loginPrompt = true
            AccountAccess.SessionLoading -> message = "جارٍ تجهيز الحساب"
            is AccountAccess.Allowed -> action()
        }
    }
    fun perform(action: suspend () -> CommunityOperation) {
        scope.launch {
            // No optimistic counts/comments, fake success or local social persistence.
            message = when (val result = action()) {
                CommunityOperation.NotConfigured -> "المشاركة ستتوفر لاحقًا"
                is CommunityOperation.Failed -> result.message
                CommunityOperation.Completed -> null
            }
        }
    }
    val chapter = context.target.targetType == CommunityTargetType.CHAPTER
    val ratingFeature = if (chapter) AccountFeature.CHAPTER_RATINGS else AccountFeature.MANGA_RATINGS
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val body: @Composable () -> Unit = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (expanded) Text(context.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("التقييم", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        snapshot.rating?.let {
                            Text(it.average?.let { average -> "★ ${"%.1f".format(average)} · ${it.count} تقييم" } ?: "${it.count} تقييم",
                                color = MangaroDesignSystem.GoldPrimary)
                        } ?: Text("لا تتوفر تقييمات بعد", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { gated(ratingFeature) { ratingVisible = true } }) {
                        Text(if (chapter) "قيّم الفصل" else "قيّم العمل", color = MangaroDesignSystem.GoldPrimary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("التعليقات", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    snapshot.commentCount?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.weight(1f))
                    if (!expanded) TextButton(onClick = { onAllComments?.invoke() }) {
                        Text("عرض كل التعليقات", color = MangaroDesignSystem.LavenderPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (snapshot.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!snapshot.loading && snapshot.comments.isEmpty()) Text(
                    if (chapter) "لا توجد تعليقات على هذا الفصل بعد" else "لا توجد تعليقات بعد",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7A9C4))
                if (!repository.available) Text("التعليقات والتقييمات ستتوفر لاحقًا", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
                if (!expanded) {
                    snapshot.comments.take(2).forEach { comment ->
                        MangaroComment(comment, onLike = { gated(AccountFeature.REACTIONS) { perform { repository.react(comment) } } },
                            onReply = { gated(AccountFeature.REPLIES) { onAllComments?.invoke() } })
                    }
                }
                if (expanded) {
                    if (session is AccountSession.Authenticated) {
                        CommunityComposer(context.target, repository.available, reply, editing,
                            onCancel = { reply = null; editing = null },
                            onSubmit = { body ->
                                gated(if (reply != null) AccountFeature.REPLIES else AccountFeature.COMMENTS) {
                                    perform {
                                        editing?.let { repository.editOwned(it, body) } ?: repository.post(context.target, body, reply?.id)
                                    }
                                }
                            })
                    } else TextButton(onClick = { gated(AccountFeature.COMMENTS) {} }) { Text("اكتب تعليقًا...") }
                }
                message?.let { Text(it, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (expanded) LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
            item(key = "summary") { body() }
            items(snapshot.comments, key = { it.id }) { comment ->
                val owned = comment.isOwnedByCurrentUser && account.featureGate.ownsContent(comment.userId)
                MangaroComment(comment,
                    onLike = { gated(AccountFeature.REACTIONS) { perform { repository.react(comment) } } },
                    onReply = { gated(AccountFeature.REPLIES) { editing = null; reply = comment } },
                    onReport = { gated(AccountFeature.COMMENTS) { perform { repository.report(comment) } } },
                    onEdit = ({ gated(AccountFeature.COMMENTS) { reply = null; editing = comment } }).takeIf { owned },
                    onDelete = ({ gated(AccountFeature.COMMENTS) { perform { repository.deleteOwned(comment) } } }).takeIf { owned })
            }
        } else body()
        if (loginPrompt) AccountRequiredPrompt(onDismiss = { loginPrompt = false }, onAccount = onAccount)
        if (ratingVisible) RatingDialog(context, snapshot.rating?.currentUserRating, repository.available,
            onDismiss = { ratingVisible = false }, onSubmit = { stars ->
                gated(ratingFeature) { perform { repository.rate(context.target, stars) }; ratingVisible = false }
            })
    }
}

@Composable
private fun RatingDialog(context: CommunityContext, current: Int?, enabled: Boolean, onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var stars by remember(context.target) { mutableIntStateOf(current ?: 0) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.SurfaceDark,
        title = { Text(if (context.target.targetType == CommunityTargetType.CHAPTER) "قيّم الفصل" else "قيّم العمل") },
        text = { Column { Text(context.title); Row { (1..5).forEach { value ->
            IconButton(onClick = { stars = value }, modifier = Modifier.size(42.dp)) {
                Icon(if (value <= stars) Icons.Outlined.Star else Icons.Outlined.StarBorder, "$value من 5", tint = MangaroDesignSystem.GoldPrimary)
            }
        } } } },
        confirmButton = { TextButton(enabled = enabled && stars in 1..5, onClick = { onSubmit(stars) }) { Text("حفظ التقييم") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
private fun CommunityComposer(target: CommunityTarget, enabled: Boolean, reply: CommunityComment?, editing: CommunityComment?, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
    var body by remember(target, reply?.id, editing?.id) { mutableStateOf(editing?.body.orEmpty()) }
    Column {
        reply?.let { Text("رد على ${it.displayName}", style = MaterialTheme.typography.labelSmall) }
        OutlinedTextField(body, { body = it }, placeholder = { Text("اكتب تعليقًا...") }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp), minLines = 2, maxLines = 4)
        Row {
            TextButton(onClick = { onSubmit(body.trim()) }, enabled = enabled && body.isNotBlank()) { Text(if (editing == null) "إرسال" else "حفظ") }
            TextButton(onClick = { body = ""; onCancel() }) { Text("إلغاء") }
        }
    }
}

@Composable
fun MangaroComment(comment: CommunityComment, onLike: () -> Unit, onReply: () -> Unit,
    onReport: (() -> Unit)? = null, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
) {
    val time = remember(comment.createdAt, comment.updatedAt) {
        DateUtils.getRelativeTimeSpanString(comment.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (comment.avatarUrl != null) AsyncImage(comment.avatarUrl, null, modifier = Modifier.size(32.dp))
            else Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(32.dp))
            Column {
                Text(comment.displayName, style = MaterialTheme.typography.titleSmall)
                comment.username?.let { Text("@$it", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC)) }
                Text("${comment.rankTitle} · المستوى ${comment.level}", style = MaterialTheme.typography.labelSmall,
                    color = if (comment.level <= 5) Color(0xFF9C96A2) else Color(0xFFB7A9C4))
            }
        }
        Text(comment.body, style = MaterialTheme.typography.bodyMedium)
        Text(time + if (comment.isEdited) " · معدّل" else "", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
        FlowRow(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(onClick = onLike) { Icon(Icons.Outlined.FavoriteBorder, "إعجاب", Modifier.size(16.dp)); Text(" ${comment.likeCount}") }
            TextButton(onClick = onReply) { Text(if (comment.replyCount > 0) "رد · ${comment.replyCount}" else "رد") }
            onReport?.let { TextButton(onClick = it) { Text("إبلاغ") } }
            if (comment.isOwnedByCurrentUser) {
                onEdit?.let { TextButton(onClick = it) { Text("تعديل") } }
                onDelete?.let { TextButton(onClick = it) { Text("حذف") } }
            }
        }
    }
}
