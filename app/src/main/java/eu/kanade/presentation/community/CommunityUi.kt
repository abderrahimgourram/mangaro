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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
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
import eu.kanade.presentation.account.UsernameHandle
import eu.kanade.presentation.account.AccountRequiredPrompt
import eu.kanade.presentation.account.AccountPanel
import eu.kanade.presentation.account.AccountScreen
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
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
fun communityContextFor(manga: Manga, chapter: Chapter? = null): CommunityContext {
    val mangaKey = CommunityMangaKey.fromSource(manga.source, manga.url)
    val chapterKey = chapter?.let {
        // Multiple candidate remote IDs are ambiguous: retain the source URL rather than guessing.
        val remote = ChapterIdentity.remoteIds(it, manga.source).singleOrNull()
        CommunityChapterKey.fromSource(mangaKey, it.url, remote)
    }
    return CommunityContext(
        CommunityTarget(if (chapter == null) CommunityTargetType.MANGA else CommunityTargetType.CHAPTER,
            mangaKey, chapterKey),
        chapter?.name ?: manga.title,
    )
}

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
    var accountVisible by rememberSaveable(context.target) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.BackgroundDark) {
        BackHandler(enabled = accountVisible) { accountVisible = false }
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
    var profilePrompt by remember { mutableStateOf(false) }
    var actionInFlight by remember(context.target) { mutableStateOf(false) }
    val actor = (session as? AccountSession.Authenticated)?.profile?.userId
    var submissionVersion by rememberSaveable(context.target, actor) { mutableIntStateOf(0) }
    var expandedReplies by rememberSaveable(context.target, actor) { mutableStateOf(emptyList<String>()) }
    var ratingVisible by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var replyId by rememberSaveable(context.target, actor) { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable(context.target, actor) { mutableStateOf<String?>(null) }
    val selectedComments = snapshot.comments + snapshot.replies.values.flatten()
    val reply = selectedComments.firstOrNull { it.id == replyId }
    val editing = selectedComments.firstOrNull { it.id == editingId }
    fun gated(feature: AccountFeature, action: () -> Unit) {
        when (account.featureGate.request(feature)) {
            is AccountAccess.LoginRequired -> { profilePrompt = false; loginPrompt = true }
            is AccountAccess.ProfileRequired -> { profilePrompt = true; loginPrompt = true }
            AccountAccess.SessionLoading -> message = "جارٍ تجهيز الحساب"
            is AccountAccess.Allowed -> action()
        }
    }
    fun perform(onSuccess: () -> Unit = {}, action: suspend () -> CommunityOperation) {
        if (actionInFlight) return
        actionInFlight = true
        val actionOwner = (account.session.value as? AccountSession.Authenticated)?.profile?.userId
        scope.launch {
            // No optimistic counts/comments, fake success or local social persistence.
            try {
                message = when (val result = action()) {
                    CommunityOperation.NotConfigured -> "المشاركة ستتوفر لاحقًا"
                    is CommunityOperation.Failed -> {
                        if (result.error.kind == CommunityErrorKind.AUTH_REQUIRED || result.error.kind == CommunityErrorKind.PROFILE_INCOMPLETE) {
                            profilePrompt = result.error.kind == CommunityErrorKind.PROFILE_INCOMPLETE
                            loginPrompt = true
                        }
                        result.error.userMessage
                    }
                    CommunityOperation.Completed -> {
                        if ((account.session.value as? AccountSession.Authenticated)?.profile?.userId == actionOwner) onSuccess()
                        null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = CommunityError(CommunityErrorKind.UNKNOWN).userMessage
            } finally { actionInFlight = false }
        }
    }
    LaunchedEffect(repository, context.target, actor) {
        // loadInitial is idempotent/cache-aware; all paging state belongs to the existing repository.
        try {
            val result = repository.loadInitial(context.target)
            // Restore only previously expanded threads, without refetching every reply on recomposition.
            expandedReplies.forEach { repository.loadReplies(context.target, it) }
            if (result is CommunityOperation.Failed) message = result.error.userMessage
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = CommunityError(CommunityErrorKind.UNKNOWN).userMessage
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
                            Text(it.average?.let { average -> "★ ${"%.1f".format(average)} · ${it.count} تقييم" } ?: "لا توجد تقييمات بعد",
                                color = MangaroDesignSystem.GoldPrimary)
                        } ?: Text(if(snapshot.loading) "جارٍ تحميل التقييم" else if(snapshot.error!=null) "تعذّر تحميل التقييم" else "لا توجد تقييمات بعد", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !actionInFlight, onClick = { gated(ratingFeature) { ratingVisible = true } }) {
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
                if (snapshot.loading || snapshot.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                snapshot.error?.let {
                    Text(it.userMessage, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { perform { repository.refresh(context.target) } }, enabled = !actionInFlight) { Text("إعادة المحاولة") }
                }
                if (!snapshot.loading && !snapshot.refreshing && snapshot.error == null && snapshot.comments.isEmpty()) Text(
                    if (chapter) "لا توجد تعليقات على هذا الفصل بعد" else "لا توجد تعليقات بعد",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7A9C4))
                if (!repository.available) Text("التعليقات والتقييمات ستتوفر لاحقًا", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
                if (!expanded) {
                    snapshot.comments.take(2).forEach { comment ->
                        MangaroComment(comment, enabled = !actionInFlight, onLike = { gated(AccountFeature.REACTIONS) { perform { repository.setLiked(comment, !comment.isLikedByCurrentUser) } } },
                            onReply = { gated(AccountFeature.REPLIES) { onAllComments?.invoke() } })
                    }
                }
                if (expanded) {
                    if (session is AccountSession.Authenticated) {
                        CommunityComposer(context.target, actor, repository.available && !actionInFlight && (editingId == null || editing != null), reply, editing, replyId, editingId, submissionVersion,
                            onCancel = { replyId = null; editingId = null },
                            onSubmit = { body, requestId ->
                                val submittedEdit = editing
                                val submittedParent = replyId
                                gated(if (submittedParent != null) AccountFeature.REPLIES else AccountFeature.COMMENTS) {
                                    perform(onSuccess = { submissionVersion++; replyId = null; editingId = null }) {
                                        submittedEdit?.let { repository.editOwned(it, body) } ?: repository.post(context.target, body, submittedParent, requestId)
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
                MangaroComment(comment, enabled = !actionInFlight,
                    onLike = { gated(AccountFeature.REACTIONS) { perform { repository.setLiked(comment, !comment.isLikedByCurrentUser) } } },
                    onReply = { gated(AccountFeature.REPLIES) { editingId = null; replyId = comment.id } },
                    onReport = { gated(AccountFeature.COMMENTS) { perform { repository.report(comment) } } },
                    onEdit = ({ gated(AccountFeature.COMMENTS) { replyId = null; editingId = comment.id } }).takeIf { owned },
                    onDelete = ({ gated(AccountFeature.COMMENTS) { perform { repository.deleteOwned(comment) } } }).takeIf { owned },
                    onViewReplies = ({
                        if (comment.id in expandedReplies) expandedReplies = expandedReplies - comment.id
                        else {
                            expandedReplies = (expandedReplies + comment.id).distinct()
                            perform { repository.loadReplies(context.target, comment.id) }
                        }
                    }).takeIf { comment.replyCount > 0 })
                if (comment.id in expandedReplies) {
                    Column(Modifier.padding(start = 16.dp)) {
                        snapshot.replies[comment.id].orEmpty().forEach { response ->
                            val replyOwned = response.isOwnedByCurrentUser && account.featureGate.ownsContent(response.userId)
                            MangaroComment(response, enabled = !actionInFlight,
                                onLike = { gated(AccountFeature.REACTIONS) { perform { repository.setLiked(response, !response.isLikedByCurrentUser) } } },
                                onReply = { gated(AccountFeature.REPLIES) { editingId = null; replyId = comment.id } },
                                onReport = { gated(AccountFeature.COMMENTS) { perform { repository.report(response) } } },
                                onEdit = ({ gated(AccountFeature.COMMENTS) { replyId = null; editingId = response.id } }).takeIf { replyOwned },
                                onDelete = ({ gated(AccountFeature.COMMENTS) { perform { repository.deleteOwned(response) } } }).takeIf { replyOwned })
                        }
                        if (comment.id in snapshot.loadingReplies) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else snapshot.replyCursors[comment.id]?.let { cursor ->
                            TextButton(onClick = { perform { repository.loadReplies(context.target, comment.id, cursor) } }) { Text("عرض المزيد من الردود") }
                        }
                    }
                }
            }
            if (snapshot.hasMore) item(key = "load-more") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (snapshot.loadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(enabled = repository.available && !snapshot.refreshing, onClick = {
                        snapshot.nextCursor?.let { cursor -> perform { repository.loadMore(context.target, cursor) } }
                    }) { Text("عرض المزيد") }
                }
            }
        } else body()
        if (loginPrompt) AccountRequiredPrompt(onDismiss = { loginPrompt = false }, onAccount = onAccount, profileIncomplete = profilePrompt)
        if (ratingVisible) RatingDialog(context, snapshot.rating?.currentUserRating, repository.available && !actionInFlight,
            onDismiss = { ratingVisible = false }, onSubmit = { stars ->
                gated(ratingFeature) { perform(onSuccess = { ratingVisible = false }) { repository.rate(context.target, stars) } }
            })
    }
}

@Composable
private fun RatingDialog(context: CommunityContext, current: Int?, enabled: Boolean, onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var stars by rememberSaveable(context.target) { mutableIntStateOf(current ?: 0) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.SurfaceDark,
        title = { Text(if (context.target.targetType == CommunityTargetType.CHAPTER) "قيّم الفصل" else "قيّم العمل") },
        text = { Column { Text(context.title); Row { (1..5).forEach { value ->
            IconButton(enabled = enabled, onClick = { stars = value }, modifier = Modifier.size(42.dp)) {
                Icon(if (value <= stars) Icons.Outlined.Star else Icons.Outlined.StarBorder, "$value من 5", tint = MangaroDesignSystem.GoldPrimary)
            }
        } } } },
        confirmButton = { TextButton(enabled = enabled && stars in 1..5, onClick = { onSubmit(stars) }) { Text("حفظ التقييم") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
private fun CommunityComposer(target: CommunityTarget, actor: String?, enabled: Boolean, reply: CommunityComment?, editing: CommunityComment?, replyId: String?, editingId: String?, submissionVersion: Int, onCancel: () -> Unit, onSubmit: (String, String) -> Unit) {
    var body by rememberSaveable(target, actor, replyId, editingId, submissionVersion) { mutableStateOf(editing?.body.orEmpty()) }
    var requestId by rememberSaveable(target, actor, replyId, editingId, submissionVersion) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val validation = remember(body) { CommunityCommentInput.validate(body) }
    Column {
        reply?.let { Text("رد على ${it.displayName}", style = MaterialTheme.typography.labelSmall) }
        OutlinedTextField(body, { if (body != it) requestId = java.util.UUID.randomUUID().toString(); body = it }, enabled = enabled, placeholder = { Text("اكتب تعليقًا...") }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp), minLines = 2, maxLines = 4,
            isError = body.isNotEmpty() && validation is CommentValidation.Invalid,
            supportingText = if (body.isNotEmpty() && validation is CommentValidation.Invalid) {{
                Text(CommunityError(CommunityErrorKind.VALIDATION, validation.issue).userMessage)
            }} else null)
        Row {
            TextButton(onClick = { (validation as? CommentValidation.Valid)?.let { onSubmit(it.body, requestId) } },
                enabled = enabled && validation is CommentValidation.Valid) { Text(if (editing == null) "إرسال" else "حفظ") }
            TextButton(enabled = enabled, onClick = { body = ""; requestId = java.util.UUID.randomUUID().toString(); onCancel() }) { Text("إلغاء") }
        }
    }
}

@Composable
fun MangaroComment(comment: CommunityComment, enabled: Boolean = true, onLike: () -> Unit, onReply: () -> Unit,
    onReport: (() -> Unit)? = null, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onViewReplies: (() -> Unit)? = null,
) {
    val time = remember(comment.createdAt, comment.updatedAt) {
        DateUtils.getRelativeTimeSpanString(comment.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp)) {
                Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.matchParentSize())
                comment.avatarUrl?.let { AsyncImage(it, null, modifier = Modifier.matchParentSize()) }
            }
            Column {
                Text(comment.displayName, style = MaterialTheme.typography.titleSmall)
                comment.username?.let { UsernameHandle(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC)) }
                Text("${comment.rankTitle} · المستوى ${comment.level}", style = MaterialTheme.typography.labelSmall,
                    color = if (comment.level <= 5) Color(0xFF9C96A2) else Color(0xFFB7A9C4))
            }
        }
        Text(comment.body, style = MaterialTheme.typography.bodyMedium)
        Text(time + if (comment.isEdited) " · معدّل" else "", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC))
        FlowRow(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(enabled = enabled, onClick = onLike) { Icon(Icons.Outlined.FavoriteBorder, "إعجاب", Modifier.size(16.dp)); Text(" ${comment.likeCount}") }
            TextButton(enabled = enabled, onClick = onReply) { Text(if (comment.replyCount > 0) "رد · ${comment.replyCount}" else "رد") }
            onViewReplies?.let { TextButton(enabled = enabled, onClick = it) { Text("عرض الردود · ${comment.replyCount}") } }
            onReport?.let { TextButton(enabled = enabled, onClick = it) { Text("إبلاغ") } }
            if (comment.isOwnedByCurrentUser) {
                onEdit?.let { TextButton(enabled = enabled, onClick = it) { Text("تعديل") } }
                onDelete?.let { TextButton(enabled = enabled, onClick = it) { Text("حذف") } }
            }
        }
    }
}
