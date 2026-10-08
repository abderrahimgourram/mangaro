package eu.kanade.presentation.community

import eu.kanade.presentation.account.DeveloperBadge
import eu.kanade.presentation.account.RankIdentity
import eu.kanade.presentation.account.TierAvatarFrame
import eu.kanade.presentation.account.rankAccent
import androidx.compose.foundation.border
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import eu.kanade.presentation.account.ProfileDisplayName
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
import tachiyomi.core.common.util.lang.WesternDigits
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

class CommunityCommentsScreen(private val context: CommunityContext, private val initialThreadId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Column(Modifier.fillMaxSize().background(MangaroDesignSystem.BackgroundDark).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") }
                Text(if (context.target.targetType == CommunityTargetType.CHAPTER) "تعليقات الفصل" else "تعليقات العمل",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            }
            CommunityContent(context, expanded = true, initialThreadId = initialThreadId, onAccount = { navigator.push(AccountScreen()) },
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.BackgroundDark,
        dragHandle = { CommunitySheetHandle() }) {
        BackHandler(enabled = accountVisible || publicUser != null) { accountVisible = false; publicUser = null }
        if (accountVisible) Box(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            AccountPanel(onBack = { accountVisible = false })
        } else if (publicUser != null) Box(Modifier.fillMaxWidth().height(620.dp)) {
            PublicCommunityProfilePanel(publicUser!!, onBack = { publicUser = null })
        } else Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            Text("تعليقات الفصل", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = Color.White)
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
    onProfile: (String) -> Unit, initialThreadId: String? = null,
) {
    val repository = remember { Injekt.get<CommunityRepository>() }
    val account = remember { Injekt.get<AccountFoundation>() }
    val snapshot by remember(context.target) { repository.observe(context.target) }.collectAsState()
    val session by account.session.collectAsState()
    val scope = rememberCoroutineScope()
    val actor = (session as? AccountSession.Authenticated)?.profile?.userId
    var loginPrompt by remember(context.target, actor) { mutableStateOf(false) }
    var profilePrompt by remember(context.target, actor) { mutableStateOf(false) }
    var actionInFlight by remember(context.target) { mutableStateOf(false) }
    var submissionVersion by rememberSaveable(context.target, actor) { mutableIntStateOf(0) }
    var threadId by rememberSaveable(context.target, actor) { mutableStateOf<String?>(initialThreadId) }
    var threadAnchor by remember(context.target, actor) { mutableStateOf<CommunityComment?>(null) }
    var threadEditingId by rememberSaveable(context.target, actor) { mutableStateOf<String?>(null) }
    var threadSubmissionVersions by rememberSaveable(context.target, actor) { mutableStateOf(mapOf<String, Int>()) }
    // Keep draft + idempotency ID when the sheet leaves composition, isolated by account/parent/edit.
    val replyDrafts = rememberSaveableStateHolder()
    var ratingVisible by remember(context.target, actor) { mutableStateOf(false) }
    var message by remember(context.target, actor) { mutableStateOf<String?>(null) }
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
                val result = action()
                if ((account.session.value as? AccountSession.Authenticated)?.profile?.userId != actionOwner) return@launch
                message = when (result) {
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
            threadId?.let {
                if (threadAnchor == null) threadAnchor = repository.commentById(context.target, it)
                if (threadAnchor != null || snapshot.comments.any { comment -> comment.id == it }) repository.loadReplies(context.target, it)
                else { threadId = null; message = "لم يعد هذا التعليق متاحًا" }
            }
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
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (expanded) Text(context.title, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = Color(0xFFB7A9C4), maxLines = 2, overflow = TextOverflow.Ellipsis)
                CommunityRatingOverview(snapshot, enabled = !actionInFlight,
                    actionLabel = if (chapter) "قيّم الفصل" else "قيّم العمل",
                    onRate = { gated(ratingFeature) { ratingVisible = true } })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("التعليقات", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                    snapshot.commentCount?.let { Text(it.toString(), color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelMedium) }
                    Spacer(Modifier.weight(1f))
                    if (expanded) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("الأكثر" to CommunityCommentOrder.MOST_LIKED, "الأحدث" to CommunityCommentOrder.NEWEST).forEach { (label, order) ->
                            FilterChip(selected = snapshot.order == order, enabled = !actionInFlight && !snapshot.loading && !snapshot.refreshing,
                                onClick = { perform { repository.setCommentOrder(context.target, order) } },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                shape = RoundedCornerShape(9.dp), colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent, labelColor = Color(0xFFB7A9C4),
                                    selectedContainerColor = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.12f), selectedLabelColor = MangaroDesignSystem.GoldPrimary),
                                border = null)
                        }
                    } else TextButton(onClick = { onAllComments?.invoke() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("عرض كل التعليقات", color = MangaroDesignSystem.LavenderPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (snapshot.loading || snapshot.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),
                    color = MangaroDesignSystem.GoldPrimary, trackColor = MangaroDesignSystem.SurfaceHigh)
                snapshot.error?.let {
                    Text(it.userMessage, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { perform { repository.refresh(context.target) } }, enabled = !actionInFlight) { Text("إعادة المحاولة") }
                }
                if (!snapshot.loading && !snapshot.refreshing && snapshot.error == null && snapshot.comments.isEmpty()) Text(
                    if (chapter) "لا توجد تعليقات على هذا الفصل بعد" else "لا توجد تعليقات بعد",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB7A9C4))
                if (initialThreadId != null && threadId != null && threadAnchor == null && !snapshot.loading && !snapshot.refreshing) {
                    TextButton(enabled = !actionInFlight, onClick = {
                        val id = threadId ?: return@TextButton
                        perform {
                            threadAnchor = repository.commentById(context.target, id)
                            if (threadAnchor != null) repository.loadReplies(context.target, id)
                            else { threadId = null; message = "لم يعد هذا التعليق متاحًا"; CommunityOperation.Completed }
                        }
                    }) { Text("فتح الردود") }
                }
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
                            onSubmit = { body, requestId, spoiler ->
                                val submittedEdit = editing
                                val submittedParent = replyId
                                gated(if (submittedParent != null) AccountFeature.REPLIES else AccountFeature.COMMENTS) {
                                    perform(onSuccess = { submissionVersion++; replyId = null; editingId = null }) {
                                        submittedEdit?.let { repository.editOwned(it, body, spoiler) } ?: repository.post(context.target, body, submittedParent, requestId, spoiler)
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
                HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = Color(0xFF2B2034))
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
            containerColor = MangaroDesignSystem.BackgroundDark, dragHandle = { CommunitySheetHandle() }) {
            BackHandler { threadId = null; threadEditingId = null }
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).imePadding()) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    item(key = "thread-header") {
                        Row(Modifier.fillMaxWidth().padding(end = 16.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { threadId = null; threadEditingId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "رجوع") }
                            Text("الردود", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(thread.replyCount.toString(), Modifier.padding(start = 8.dp), color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelMedium)
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
                        Column(Modifier.padding(start = 14.dp, end = 4.dp).drawBehind {
                            val x = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f
                            drawLine(Color(0xFF3A2B46), androidx.compose.ui.geometry.Offset(x, 0f),
                                androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        }) {
                            MangaroComment(response, enabled = !actionInFlight, replyTo = thread,
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
                }
                HorizontalDivider(color = Color(0xFF2B2034))
                Column(Modifier.fillMaxWidth().background(MangaroDesignSystem.SurfaceDark.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
                    if (session is AccountSession.Authenticated) replyDrafts.SaveableStateProvider("${context.target}:$actor:${thread.id}:$threadEditingId:${threadSubmissionVersions[thread.id] ?: 0}") {
                        CommunityComposer(context.target, actor, repository.available && !actionInFlight && (threadEditingId == null || threadEditing != null),
                            thread, threadEditing, thread.id, threadEditingId, threadSubmissionVersions[thread.id] ?: 0,
                            avatar = (session as? AccountSession.Authenticated)?.profile?.let(AccountAuthor::fromProfile),
                            onCancel = { threadEditingId = null }, onSubmit = { text, requestId, spoiler ->
                                val edited = threadEditing
                                gated(AccountFeature.REPLIES) {
                                    perform(onSuccess = {
                                        threadSubmissionVersions = threadSubmissionVersions + (thread.id to ((threadSubmissionVersions[thread.id] ?: 0) + 1))
                                        threadEditingId = null
                                    }) {
                                        edited?.let { repository.editOwned(it, text, spoiler) } ?: repository.post(context.target, text, thread.id, requestId, spoiler)
                                    }
                                }
                            })
                    } else TextButton(onClick = { gated(AccountFeature.REPLIES) {} }) { Text("اكتب ردًا...") }
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
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MangaroDesignSystem.SurfaceDark)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MangaroDesignSystem.GoldPrimary.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Star, null, Modifier.size(24.dp), tint = MangaroDesignSystem.GoldPrimary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("التقييم", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(summary?.average?.let { WesternDigits.format("%.1f", it) } ?: "—", color = Color.White,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("/ 5", color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(when {
                summary != null && summary.count > 0 -> "${summary.count} تقييم"
                snapshot.loading -> "جارٍ تحميل التقييم"
                snapshot.error != null -> "تعذّر تحميل التقييم"
                else -> "لا توجد تقييمات بعد"
            }, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CommunityAction(label = actionLabel, icon = Icons.Outlined.StarBorder,
                enabled = enabled, active = true, onClick = onRate)
            summary?.currentUserRating?.let { stars ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("تقييمك", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row { (1..5).forEach { Icon(if (it <= stars) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            null, Modifier.size(12.dp), tint = MangaroDesignSystem.GoldPrimary) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunitySheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 28.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF685573)))
    }
}

@Composable
private fun RatingDialog(context: CommunityContext, current: Int?, enabled: Boolean, onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var stars by rememberSaveable(context.target) { mutableIntStateOf(current ?: 0) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = MangaroDesignSystem.SurfaceDark, shape = RoundedCornerShape(24.dp),
        title = { Text(if (context.target.targetType == CommunityTargetType.CHAPTER) "قيّم الفصل" else "قيّم العمل", fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        else ProfileDisplayName(name, color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CommunityComposer(target: CommunityTarget, actor: String?, enabled: Boolean, reply: CommunityComment?, editing: CommunityComment?, replyId: String?, editingId: String?, submissionVersion: Int,
    avatar: AccountAuthor?, onCancel: () -> Unit, onSubmit: (String, String, Boolean) -> Unit) {
    var body by rememberSaveable(target, actor, replyId, editingId, submissionVersion) { mutableStateOf(editing?.body.orEmpty()) }
    var requestId by rememberSaveable(target, actor, replyId, editingId, submissionVersion) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var spoiler by rememberSaveable(target, actor, replyId, editingId, submissionVersion) { mutableStateOf(editing?.spoiler ?: false) }
    val validation = remember(body) { CommunityCommentInput.validate(body) }
    var focused by remember { mutableStateOf(false) }
    val outline by animateColorAsState(if (focused) MangaroDesignSystem.GoldPrimary.copy(alpha = 0.45f) else Color(0xFF3A2B46), tween(140), label = "composerFocus")
    val canSend = enabled && validation is CommentValidation.Valid
    val sendColor by animateColorAsState(if (canSend) MangaroDesignSystem.GoldPrimary else MangaroDesignSystem.SurfaceHigh, tween(140), label = "composerSend")
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(if (sendPressed) 0.94f else 1f, tween(120), label = "composerPress")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (reply != null || editing != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (editing != null) Text("تعديل التعليق", color = Color(0xFF9F90AC), style = MaterialTheme.typography.labelSmall)
                else reply?.let { ReplyAttribution(it.username, it.displayName) }
            }
            TextButton(enabled = enabled, onClick = { body = ""; requestId = java.util.UUID.randomUUID().toString(); onCancel() }) { Text("إلغاء", style = MaterialTheme.typography.labelSmall) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            ProfileAvatar(avatar?.avatarUrl, modifier = Modifier.padding(top = 8.dp).size(32.dp).clip(RoundedCornerShape(11.dp)).background(MangaroDesignSystem.SurfaceHigh))
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MangaroDesignSystem.SurfaceDark)
                .border(1.dp, outline, RoundedCornerShape(14.dp)).heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(body, { if (body != it) requestId = java.util.UUID.randomUUID().toString(); body = it }, enabled = enabled,
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }.padding(vertical = 6.dp), maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, textDirection = TextDirection.Content, lineHeight = 20.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MangaroDesignSystem.GoldPrimary),
                    decorationBox = { field ->
                        Box { if (body.isEmpty()) Text("اكتب تعليقًا...", color = Color(0xFF9F90AC), style = MaterialTheme.typography.bodyMedium); field() }
                    })
                IconButton(onClick = { (validation as? CommentValidation.Valid)?.let { onSubmit(it.body, requestId, spoiler) } },
                    enabled = canSend, interactionSource = sendInteraction, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(32.dp).graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                        .clip(RoundedCornerShape(10.dp)).background(sendColor), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.Send, if (editing == null) "إرسال" else "حفظ", Modifier.size(18.dp),
                            tint = if (canSend) MangaroDesignSystem.BackgroundDark else Color(0xFF9F90AC))
                    }
                }
            }
        }
        Row(Modifier.padding(start = 40.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { spoiler = !spoiler; requestId = java.util.UUID.randomUUID().toString() },
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = spoiler, onCheckedChange = null, enabled = enabled,
                modifier = Modifier.size(36.dp), colors = CheckboxDefaults.colors(checkedColor = MangaroDesignSystem.GoldPrimary))
            Text("يحتوي على حرق", color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
        }
        if (body.isNotEmpty() && validation is CommentValidation.Invalid)
            Text(CommunityError(CommunityErrorKind.VALIDATION, validation.issue).userMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MangaroComment(comment: CommunityComment, enabled: Boolean = true, onLike: () -> Unit, onReply: () -> Unit,
    onReport: (() -> Unit)? = null, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onViewReplies: (() -> Unit)? = null,
    replyTo: CommunityComment? = null, onAuthor: () -> Unit,
) {
    val time = remember(comment.createdAt, comment.updatedAt) {
        WesternDigits.normalize(DateUtils.getRelativeTimeSpanString(comment.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
    }
    var menu by remember(comment.id) { mutableStateOf(false) }
    val avatarInteraction = remember(comment.id) { MutableInteractionSource() }
    val avatarPressed by avatarInteraction.collectIsPressedAsState()
    val avatarScale by animateFloatAsState(if (avatarPressed) 0.96f else 1f, tween(120), label = "authorPress")
    val metadata = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .clickable(interactionSource = avatarInteraction, indication = ripple(), onClick = onAuthor), contentAlignment = Alignment.Center) {
                TierAvatarFrame(comment.level, Modifier.fillMaxSize()) {
                    ProfileAvatar(comment.avatarUrl, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = avatarScale; scaleY = avatarScale }
                        .clip(RoundedCornerShape(11.dp)).background(MangaroDesignSystem.SurfaceHigh))
                }
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onAuthor),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    ProfileDisplayName(comment.author.displayName, comment.username, color = if (comment.level >= 5) rankAccent(comment.level) else Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content), fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DeveloperBadge(comment.author.role, compact = true)
                    comment.username?.let { UsernameHandle(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB7A9C4)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    RankIdentity(comment.level)
                    Text("·", color = Color(0xFF9F90AC), style = metadata)
                    Text(time, style = metadata, color = Color(0xFF9F90AC))
                    if (comment.isEdited) Text("معدّل", style = metadata, color = Color(0xFF9F90AC))
                }
            }
            Box {
                if (onReport != null || (comment.isOwnedByCurrentUser && (onEdit != null || onDelete != null))) {
                    IconButton(enabled = enabled, onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "خيارات التعليق", Modifier.size(19.dp), tint = Color(0xFFB7A9C4))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MangaroDesignSystem.SurfaceHigh,
                        shape = RoundedCornerShape(14.dp)) {
                        if (comment.isOwnedByCurrentUser) {
                            onEdit?.let { DropdownMenuItem(text = { Text("تعديل", style = MaterialTheme.typography.bodySmall) }, onClick = { menu = false; it() }, enabled = enabled) }
                            onDelete?.let { DropdownMenuItem(text = { Text("حذف", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE1A9B5)) }, onClick = { menu = false; it() }, enabled = enabled) }
                        }
                        onReport?.let { DropdownMenuItem(text = { Text("إبلاغ", style = MaterialTheme.typography.bodySmall) }, onClick = { menu = false; it() }, enabled = enabled) }
                    }
                }
            }
        }
        Column(Modifier.padding(start = 54.dp).animateContentSize(tween(160)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            replyTo?.let { ReplyAttribution(it.username, it.displayName) }
            var revealed by remember(comment.id, comment.spoiler, comment.body, comment.updatedAt) { mutableStateOf(false) }
            val visibleBody = comment.visibleBody(revealed)
            if (visibleBody != null) {
                Text(visibleBody, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content, lineHeight = 22.sp), color = Color(0xFFE8E0EE))
            } else {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MangaroDesignSystem.SurfaceHigh.copy(alpha = 0.55f))
                    .clickable { revealed = true }.padding(horizontal = 12.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp), tint = MangaroDesignSystem.GoldPrimary.copy(alpha = 0.8f))
                    Text("يحتوي هذا التعليق على حرق", Modifier.weight(1f), color = Color(0xFFB7A9C4), style = MaterialTheme.typography.bodySmall)
                    Text("إظهار", color = MangaroDesignSystem.GoldPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                CommunityAction(comment.likeCount.toString(), if (comment.isLikedByCurrentUser) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    enabled && (!comment.isOwnedByCurrentUser || comment.isLikedByCurrentUser), active = comment.isLikedByCurrentUser, onClick = onLike, description = "إعجاب")
                CommunityAction("رد", enabled = enabled, onClick = onReply)
                onViewReplies?.let { CommunityAction("${comment.replyCount} ردود", enabled = enabled, onClick = it,
                    tint = MangaroDesignSystem.LavenderPrimary) }
            }
        }
    }
}

/** Small visible controls keep full touch targets; state/press transitions are presentation only. */
@Composable
private fun CommunityAction(label: String, icon: ImageVector? = null, enabled: Boolean = true,
    active: Boolean = false, tint: Color = Color(0xFFB7A9C4), description: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "communityActionPress")
    val color by animateColorAsState(if (active) MangaroDesignSystem.GoldPrimary else tint, tween(140), label = "communityActionState")
    TextButton(enabled = enabled, onClick = onClick, interactionSource = interaction,
        modifier = Modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = Color(0xFF85758F))) {
        icon?.let { Icon(it, description, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
