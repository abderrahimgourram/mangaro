package eu.kanade.presentation.community

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MoreHoriz
import eu.kanade.presentation.account.ProfileAvatar
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.account.UsernameHandle
import eu.kanade.presentation.account.AccountRequiredPrompt
import eu.kanade.presentation.account.AccountPanel
import eu.kanade.presentation.account.AccountScreen
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
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
            CommunityContent(context, expanded = true, onAccount = { navigator.push(AccountScreen()) },
                onProfile = { userId -> openCommunityProfile(navigator, userId) })
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
            onAllComments = { navigator.push(CommunityCommentsScreen(context)) },
            onProfile = { userId -> openCommunityProfile(navigator, userId) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderCommunitySheet(context: CommunityContext, onDismiss: () -> Unit) {
    var accountVisible by rememberSaveable(context.target) { mutableStateOf(false) }
    var publicUser by rememberSaveable(context.target) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.BackgroundDark) {
        BackHandler(enabled = accountVisible || publicUser != null) { accountVisible = false; publicUser = null }
        if (accountVisible) Box(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            AccountPanel(onBack = { accountVisible = false })
        } else if (publicUser != null) Box(Modifier.fillMaxWidth().height(620.dp)) {
            PublicCommunityProfilePanel(publicUser!!, onBack = { publicUser = null })
        } else Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            Text("تعليقات الفصل", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            CommunityContent(context, expanded = true, onAccount = { accountVisible = true }, onProfile = { userId ->
                val own = (Injekt.get<AccountFoundation>().session.value as? AccountSession.Authenticated)?.profile?.userId
                if (userId == own) accountVisible = true else publicUser = userId
            })
        }
    }
}

private fun openCommunityProfile(navigator: cafe.adriel.voyager.navigator.Navigator, userId: String) {
    val own = (Injekt.get<AccountFoundation>().session.value as? AccountSession.Authenticated)?.profile?.userId
    if (own == userId && navigator.lastItem is AccountScreen) return
    if ((navigator.lastItem as? PublicCommunityProfileScreen)?.userId == userId) return
    navigator.push(if (own == userId) AccountScreen() else PublicCommunityProfileScreen(userId))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityContent(
    context: CommunityContext, expanded: Boolean, onAccount: () -> Unit, onAllComments: (() -> Unit)? = null,
    onProfile: (String) -> Unit,
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
    var threadId by rememberSaveable(context.target, actor) { mutableStateOf<String?>(null) }
    var threadAnchor by remember(context.target, actor) { mutableStateOf<CommunityComment?>(null) }
    var threadEditingId by rememberSaveable(context.target, actor) { mutableStateOf<String?>(null) }
    var threadSubmissionVersion by rememberSaveable(context.target, actor) { mutableIntStateOf(0) }
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
            threadId?.let { repository.loadReplies(context.target, it) }
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
                CommunityRatingOverview(snapshot, enabled = !actionInFlight,
                    actionLabel = if (chapter) "قيّم الفصل" else "قيّم العمل",
                    onRate = { gated(ratingFeature) { ratingVisible = true } })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("التعليقات", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    snapshot.commentCount?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.weight(1f))
                    if (!expanded) TextButton(onClick = { onAllComments?.invoke() }) {
                        Text("عرض كل التعليقات", color = MangaroDesignSystem.LavenderPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (expanded) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("الأكثر" to CommunityCommentOrder.MOST_LIKED, "الأحدث" to CommunityCommentOrder.NEWEST).forEach { (label, order) ->
                        FilterChip(selected = snapshot.order == order, enabled = !actionInFlight && !snapshot.loading && !snapshot.refreshing,
                            onClick = { perform { repository.setCommentOrder(context.target, order) } }, label = { Text(label) },
                            shape = RoundedCornerShape(10.dp), colors = FilterChipDefaults.filterChipColors(
                                containerColor = MangaroDesignSystem.SurfaceDark, labelColor = Color(0xFFB7A9C4),
                                selectedContainerColor = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.12f), selectedLabelColor = MangaroDesignSystem.GoldPrimary),
                            border = null)
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
                            onReply = { gated(AccountFeature.REPLIES) { onAllComments?.invoke() } },
                            onReport = { gated(AccountFeature.COMMENTS) { perform { repository.report(comment) } } },
                            onAuthor = { onProfile(comment.userId) })
                    }
                }
                if (expanded) {
                    if (session is AccountSession.Authenticated) {
                        CommunityComposer(context.target, actor, repository.available && !actionInFlight && (editingId == null || editing != null), reply, editing, replyId, editingId, submissionVersion,
                            avatar = (session as? AccountSession.Authenticated)?.profile?.let(AccountAuthor::fromProfile),
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
                    onReply = { gated(AccountFeature.REPLIES) { threadAnchor = comment; threadId = comment.id; threadEditingId = null; perform { repository.loadReplies(context.target, comment.id) } } },
                    onReport = { gated(AccountFeature.COMMENTS) { perform { repository.report(comment) } } },
                    onEdit = ({ gated(AccountFeature.COMMENTS) { replyId = null; editingId = comment.id } }).takeIf { owned },
                    onDelete = ({ gated(AccountFeature.COMMENTS) { perform { repository.deleteOwned(comment) } } }).takeIf { owned },
                    onViewReplies = ({ threadAnchor = comment; threadId = comment.id; threadEditingId = null; perform { repository.loadReplies(context.target, comment.id) } }).takeIf { comment.replyCount > 0 },
                    onAuthor = { onProfile(comment.userId) })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color(0xFF2B2034))
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
        val thread = snapshot.comments.firstOrNull { it.id == threadId } ?: threadAnchor?.takeIf { it.id == threadId }
        val threadEditing = snapshot.replies[threadId].orEmpty().firstOrNull { it.id == threadEditingId }
        if (thread != null) ModalBottomSheet(onDismissRequest = { threadId = null; threadEditingId = null },
            containerColor = MangaroDesignSystem.BackgroundDark) {
            BackHandler { threadId = null; threadEditingId = null }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp).imePadding()) {
                item(key = "thread-header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { threadId = null; threadEditingId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") }
                        Text("الردود", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text(" · ${thread.replyCount}", color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelMedium)
                    }
                    val threadOwned = thread.isOwnedByCurrentUser && account.featureGate.ownsContent(thread.userId)
                    MangaroComment(thread, enabled = !actionInFlight,
                        onLike = { gated(AccountFeature.REACTIONS) { perform { repository.setLiked(thread, !thread.isLikedByCurrentUser) } } },
                        onReply = { gated(AccountFeature.REPLIES) { threadEditingId = null } },
                        onReport = { gated(AccountFeature.COMMENTS) { perform { repository.report(thread) } } },
                        onEdit = ({ gated(AccountFeature.COMMENTS) { threadId = null; editingId = thread.id } }).takeIf { threadOwned },
                        onDelete = ({ gated(AccountFeature.COMMENTS) { perform(onSuccess = { threadId = null }) { repository.deleteOwned(thread) } } }).takeIf { threadOwned },
                        onAuthor = { threadId = null; onProfile(thread.userId) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color(0xFF3A2947))
                }
                items(snapshot.replies[thread.id].orEmpty(), key = { it.id }) { response ->
                    val owned = response.isOwnedByCurrentUser && account.featureGate.ownsContent(response.userId)
                    Column(Modifier.padding(start = 12.dp)) {
                        ReplyAttribution(thread.username, thread.displayName, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        MangaroComment(response, enabled = !actionInFlight,
                            onLike = { gated(AccountFeature.REACTIONS) { perform { repository.setLiked(response, !response.isLikedByCurrentUser) } } },
                            onReply = { gated(AccountFeature.REPLIES) { threadEditingId = null } },
                            onReport = { gated(AccountFeature.COMMENTS) { perform { repository.report(response) } } },
                            onEdit = ({ gated(AccountFeature.COMMENTS) { threadEditingId = response.id } }).takeIf { owned },
                            onDelete = ({ gated(AccountFeature.COMMENTS) { perform { repository.deleteOwned(response) } } }).takeIf { owned },
                            onAuthor = { threadId = null; onProfile(response.userId) })
                    }
                }
                item(key = "thread-paging") {
                    if (thread.id in snapshot.loadingReplies) CircularProgressIndicator(Modifier.padding(16.dp).size(18.dp), strokeWidth = 2.dp)
                    else snapshot.replyCursors[thread.id]?.let { cursor ->
                        TextButton(enabled = !actionInFlight, onClick = { perform { repository.loadReplies(context.target, thread.id, cursor) } }) { Text("عرض المزيد من الردود") }
                    }
                    if (thread.id !in snapshot.loadingReplies && snapshot.replies[thread.id].isNullOrEmpty())
                        Text("لا توجد ردود بعد", Modifier.padding(16.dp), color = Color(0xFF9F90AC), style = MaterialTheme.typography.bodySmall)
                    message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall) }
                    snapshot.error?.let { TextButton(enabled = !actionInFlight, onClick = { perform { repository.loadReplies(context.target, thread.id) } }) { Text("إعادة المحاولة") } }
                }
                item(key = "thread-composer") {
                    Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                        if (session is AccountSession.Authenticated) CommunityComposer(context.target, actor, repository.available && !actionInFlight,
                            thread, threadEditing, thread.id, threadEditingId, threadSubmissionVersion,
                            avatar = (session as? AccountSession.Authenticated)?.profile?.let(AccountAuthor::fromProfile),
                            onCancel = { threadEditingId = null }, onSubmit = { text, requestId ->
                                val edited = threadEditing
                                gated(AccountFeature.REPLIES) {
                                    perform(onSuccess = { threadSubmissionVersion++; threadEditingId = null }) {
                                        edited?.let { repository.editOwned(it, text) } ?: repository.post(context.target, text, thread.id, requestId)
                                    }
                                }
                            })
                        else TextButton(onClick = { gated(AccountFeature.REPLIES) {} }) { Text("اكتب ردًا...") }
                    }
                }
            }
        }
        if (loginPrompt) AccountRequiredPrompt(onDismiss = { loginPrompt = false }, onAccount = onAccount, profileIncomplete = profilePrompt)
        if (ratingVisible) RatingDialog(context, snapshot.rating?.currentUserRating, repository.available && !actionInFlight,
            onDismiss = { ratingVisible = false }, onSubmit = { stars ->
                gated(ratingFeature) { perform(onSuccess = { ratingVisible = false }) { repository.rate(context.target, stars) } }
            })
    }
}

@Composable
private fun CommunityRatingOverview(snapshot: CommunitySnapshot, enabled: Boolean, actionLabel: String, onRate: () -> Unit) {
    val summary = snapshot.rating
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MangaroDesignSystem.SurfaceDark)
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("التقييم", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Star, null, Modifier.size(22.dp), tint = MangaroDesignSystem.GoldPrimary)
                    Text(summary?.average?.let { "%.1f".format(it) } ?: "—", color = Color.White,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("/ 5", color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                }
                Text(when {
                    summary != null && summary.count > 0 -> "${summary.count} تقييم"
                    snapshot.loading -> "جارٍ تحميل التقييم"
                    snapshot.error != null -> "تعذّر تحميل التقييم"
                    else -> "لا توجد تقييمات بعد"
                }, color = Color(0xFF9F90AC), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled = enabled, onClick = onRate, shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MangaroDesignSystem.GoldPrimary,
                    containerColor = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.08f))) { Text(actionLabel) }
        }
        summary?.currentUserRating?.let { stars ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تقييمك", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row { (1..5).forEach { Icon(if (it <= stars) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        null, Modifier.size(16.dp), tint = MangaroDesignSystem.GoldPrimary) } }
                }
            }
        }
    }
}

@Composable
private fun RatingDialog(context: CommunityContext, current: Int?, enabled: Boolean, onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var stars by rememberSaveable(context.target) { mutableIntStateOf(current ?: 0) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(24.dp),
        title = { Text(if (context.target.targetType == CommunityTargetType.CHAPTER) "قيّم الفصل" else "قيّم العمل", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(context.title, color = Color(0xFFB7A9C4), maxLines = 2, overflow = TextOverflow.Ellipsis)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { (1..5).forEach { value ->
                    IconButton(enabled = enabled, onClick = { stars = value }, modifier = Modifier.size(48.dp)) {
                        Icon(if (value <= stars) Icons.Outlined.Star else Icons.Outlined.StarBorder, "$value من 5",
                            Modifier.size(30.dp), tint = if (value <= stars) MangaroDesignSystem.GoldPrimary else Color(0xFF8F819E))
                    }
                } }
            }
            if (stars > 0) Text("تقييمك: $stars من 5", color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.labelMedium)
        } },
        confirmButton = { TextButton(enabled = enabled && stars in 1..5, onClick = { onSubmit(stars) }) { Text("حفظ التقييم", color = MangaroDesignSystem.GoldPrimary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء", color = Color(0xFFB7A9C4)) } })
}

@Composable
private fun ReplyAttribution(username: String?, name: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ردًا على", color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall)
        if (username != null) UsernameHandle(username, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
        else Text(name, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CommunityComposer(target: CommunityTarget, actor: String?, enabled: Boolean, reply: CommunityComment?, editing: CommunityComment?, replyId: String?, editingId: String?, submissionVersion: Int,
    avatar: AccountAuthor?, onCancel: () -> Unit, onSubmit: (String, String) -> Unit) {
    var body by rememberSaveable(target, actor, replyId, editingId, submissionVersion) { mutableStateOf(editing?.body.orEmpty()) }
    var requestId by rememberSaveable(target, actor, replyId, editingId, submissionVersion) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val validation = remember(body) { CommunityCommentInput.validate(body) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (reply != null || editing != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (editing != null) Text("تعديل التعليق", color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall)
                else reply?.let { ReplyAttribution(it.username, it.displayName) }
            }
            TextButton(enabled = enabled, onClick = { body = ""; requestId = java.util.UUID.randomUUID().toString(); onCancel() }) { Text("إلغاء", style = MaterialTheme.typography.labelSmall) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            ProfileAvatar(avatar?.avatarUrl, modifier = Modifier.padding(top = 6.dp).size(34.dp).clip(RoundedCornerShape(12.dp)).background(MangaroDesignSystem.SurfaceHigh))
            Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MangaroDesignSystem.SurfaceHigh)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(body, { if (body != it) requestId = java.util.UUID.randomUUID().toString(); body = it }, enabled = enabled,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp), maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, textDirection = TextDirection.Content),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MangaroDesignSystem.GoldPrimary),
                    decorationBox = { field ->
                        Box { if (body.isEmpty()) Text("اكتب تعليقًا...", color = Color(0xFF9F90AC), style = MaterialTheme.typography.bodyMedium); field() }
                    })
                IconButton(onClick = { (validation as? CommentValidation.Valid)?.let { onSubmit(it.body, requestId) } },
                    enabled = enabled && validation is CommentValidation.Valid) {
                    Icon(Icons.AutoMirrored.Outlined.Send, if (editing == null) "إرسال" else "حفظ",
                        tint = if (enabled && validation is CommentValidation.Valid) MangaroDesignSystem.GoldPrimary else Color(0xFF776882))
                }
            }
        }
        if (body.isNotEmpty() && validation is CommentValidation.Invalid)
            Text(CommunityError(CommunityErrorKind.VALIDATION, validation.issue).userMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MangaroComment(comment: CommunityComment, enabled: Boolean = true, onLike: () -> Unit, onReply: () -> Unit,
    onReport: (() -> Unit)? = null, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onViewReplies: (() -> Unit)? = null,
    onAuthor: () -> Unit,
) {
    val time = remember(comment.createdAt, comment.updatedAt) {
        DateUtils.getRelativeTimeSpanString(comment.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    var menu by remember(comment.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            ProfileAvatar(comment.avatarUrl, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(MangaroDesignSystem.SurfaceHigh).clickable(onClick = onAuthor))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(comment.displayName, Modifier.clickable(onClick = onAuthor), color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                comment.username?.let { UsernameHandle(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9F90AC)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.rankTitle, style = MaterialTheme.typography.labelSmall,
                        color = if (comment.level <= 5) Color(0xFF9C96A2) else Color(0xFFB7A9C4))
                    Text("· Lv.${comment.level}", color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                }
            }
            Box {
                if (onReport != null || (comment.isOwnedByCurrentUser && (onEdit != null || onDelete != null))) {
                    IconButton(enabled = enabled, onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "خيارات التعليق", Modifier.size(20.dp), tint = Color(0xFF9F90AC))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MangaroDesignSystem.SurfaceHigh) {
                        if (comment.isOwnedByCurrentUser) {
                            onEdit?.let { DropdownMenuItem(text = { Text("تعديل") }, onClick = { menu = false; it() }, enabled = enabled) }
                            onDelete?.let { DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; it() }, enabled = enabled) }
                        }
                        onReport?.let { DropdownMenuItem(text = { Text("إبلاغ") }, onClick = { menu = false; it() }, enabled = enabled) }
                    }
                }
            }
        }
        Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(comment.body, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = Color(0xFFE4DCEB))
            Text(time + if (comment.isEdited) " · معدّل" else "", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8F819E))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = enabled, onClick = onLike, contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (comment.isLikedByCurrentUser) MangaroDesignSystem.GoldPrimary else Color(0xFFB7A9C4))) {
                    Icon(if (comment.isLikedByCurrentUser) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "إعجاب", Modifier.size(16.dp))
                    Text(" ${comment.likeCount}", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(enabled = enabled, onClick = onReply, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("رد", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelMedium) }
                onViewReplies?.let { TextButton(enabled = enabled, onClick = it, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("${comment.replyCount} ردود", color = MangaroDesignSystem.LavenderPrimary, style = MaterialTheme.typography.labelMedium)
                } }
            }
        }
    }
}
