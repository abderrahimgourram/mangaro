package eu.kanade.tachiyomi.data.library

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.core.common.util.lang.WesternDigits
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.math.RoundingMode

class LibraryUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    private val percentFormatter = WesternDigits.percentFormat().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 0
    }

    /**
     * Pending intent of action that cancels the library update
     */
    private val cancelIntent by lazy {
        NotificationReceiver.cancelLibraryUpdatePendingBroadcast(context)
    }

    /**
     * Bitmap of the app for notifications.
     */
    private val notificationBitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    /**
     * Cached progress notification to avoid creating a lot.
     */
    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setLargeIcon(notificationBitmap)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(R.drawable.ic_close_24dp, context.stringResource(MR.strings.action_cancel), cancelIntent)
        }
    }

    /**
     * Shows the notification containing the currently updating manga and the progress.
     *
     * @param manga the manga that are being updated.
     * @param current the current progress.
     * @param total the total progress.
     */
    fun showProgressNotification(manga: List<Manga>, current: Int, total: Int) {
        if (runCatching { tachiyomi.core.common.util.lang.LocalAccountScope.check() }.isFailure) return
        progressNotificationBuilder
            .setContentTitle(
                context.stringResource(
                    MR.strings.notification_updating_progress,
                    percentFormatter.format(current.toFloat() / total.coerceAtLeast(1)),
                ),
            )

        if (!securityPreferences.hideNotificationContent.get()) {
            val updatingText = manga.joinToString("\n") { it.title.chop(40) }
            progressNotificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(updatingText))
        }

        context.notify(
            Notifications.ID_LIBRARY_PROGRESS,
            progressNotificationBuilder
                .setProgress(total, current, false)
                .build(),
        )
    }

    /**
     * Warn when excessively checking any single source.
     */
    fun showQueueSizeWarningNotificationIfNeeded(mangaToUpdate: List<LibraryManga>) {
        // Source health/rate limiting already manages this queue. Its size is not an alert event.
    }

    /**
     * Shows notification containing update entries that failed with action to open full log.
     *
     * @param failed Number of entries that failed to update.
     * @param uri Uri for error log file containing all titles that failed.
     */
    fun showUpdateErrorNotification(failed: Int, uri: Uri) {
        if (runCatching { tachiyomi.core.common.util.lang.LocalAccountScope.check() }.isFailure) return
        if (failed == 0) {
            return
        }

        context.notify(
            Notifications.ID_LIBRARY_ERROR,
            Notifications.CHANNEL_LIBRARY_ERROR,
        ) {
            setContentTitle(context.pluralStringResource(MR.plurals.notification_update_error, failed, failed))
            setContentText("راجع الأعمال التي تعذّر تحديثها وأعد المحاولة")
            setOnlyAlertOnce(true)
            setAutoCancel(true)
            setSmallIcon(R.drawable.ic_book_24dp)

            setContentIntent(NotificationReceiver.openErrorLogPendingActivity(context, uri))
        }
    }

    /**
     * Shows the notification containing the result of the update done by the service.
     *
     * @param updates a list of manga with new updates.
     */
    fun showUpdateNotifications(candidates: List<Pair<Manga, Array<Chapter>>>) {
        if (runCatching { tachiyomi.core.common.util.lang.LocalAccountScope.check() }.isFailure) return
        val inbox = Injekt.get<eu.kanade.tachiyomi.data.inbox.WorkUpdateInbox>()
        val updates = inbox.claimSystemUpdates(candidates)
        if (updates.isEmpty()) return
        val owner = (Injekt.get<mihon.domain.account.AccountFoundation>().session.value as? mihon.domain.account.AccountSession.Authenticated)?.profile?.userId
        fun canPublish(): Boolean = Injekt.get<eu.kanade.tachiyomi.data.notification.NotificationPreferences>().chapters.get() &&
            (Injekt.get<mihon.domain.account.AccountFoundation>().session.value as? mihon.domain.account.AccountSession.Authenticated)?.profile?.userId == owner
        if (!canPublish()) return
        // Parent group notification
        inbox.publishUnread(owner, updates) { liveUpdates ->
        context.notify(
            Notifications.ID_NEW_CHAPTERS,
            Notifications.CHANNEL_NEW_CHAPTERS,
        ) {
            setContentTitle(context.stringResource(MR.strings.notification_new_chapters))
            if (liveUpdates.size == 1 && !securityPreferences.hideNotificationContent.get()) {
                setContentText(liveUpdates.first().first.title.chop(NOTIF_TITLE_MAX_LEN))
            } else {
                setContentText(
                    context.pluralStringResource(
                        MR.plurals.notification_new_chapters_summary,
                        liveUpdates.size,
                        liveUpdates.size,
                    ),
                )

                if (!securityPreferences.hideNotificationContent.get()) {
                    setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            liveUpdates.joinToString("\n") {
                                it.first.title.chop(NOTIF_TITLE_MAX_LEN)
                            },
                        ),
                    )
                }
            }

            setSmallIcon(R.drawable.ic_book_24dp)
            setLargeIcon(notificationBitmap)

            setGroup(Notifications.GROUP_NEW_CHAPTERS)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            setGroupSummary(true)
            setOnlyAlertOnce(true)
            priority = NotificationCompat.PRIORITY_DEFAULT

            setContentIntent(getNotificationIntent())
            setAutoCancel(true)
        }

        }

        // Per-manga notification
        if (!securityPreferences.hideNotificationContent.get()) {
            launchUI {
                val notices = updates.take(5).associate { (manga, chapters) ->
                    manga.id to NotificationManagerCompat.NotificationWithIdAndTag(
                        manga.id.hashCode(), createNewChaptersNotification(manga, chapters),
                    )
                }
                // Cover loading may outlive a sign-out/account switch or preference change.
                if (canPublish()) inbox.publishUnread(owner, updates.take(5)) { liveUpdates ->
                    context.notify(liveUpdates.mapNotNull { notices[it.first.id] })
                }
            }
        }
    }

    private suspend fun createNewChaptersNotification(manga: Manga, chapters: Array<Chapter>): Notification {
        val icon = getMangaIcon(manga)
        return context.notificationBuilder(Notifications.CHANNEL_NEW_CHAPTERS) {
            setContentTitle(manga.title)

            val description = getNewChaptersDescription(chapters)
            setContentText(description)
            setStyle(NotificationCompat.BigTextStyle().bigText(description))

            setSmallIcon(R.drawable.ic_book_24dp)

            if (icon != null) {
                setLargeIcon(icon)
            }

            setGroup(Notifications.GROUP_NEW_CHAPTERS)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            setOnlyAlertOnce(true)
            priority = NotificationCompat.PRIORITY_DEFAULT

            // Open first chapter on tap
            setContentIntent(NotificationReceiver.openChapterPendingActivity(context, manga, chapters.first()))
            setAutoCancel(true)

            // Mark chapters as read action
            addAction(
                R.drawable.ic_done_24dp,
                context.stringResource(MR.strings.action_mark_as_read),
                NotificationReceiver.markAsReadPendingBroadcast(
                    context,
                    manga,
                    chapters,
                    Notifications.ID_NEW_CHAPTERS,
                ),
            )
            // View chapters action
            addAction(
                R.drawable.ic_book_24dp,
                context.stringResource(MR.strings.action_view_chapters),
                NotificationReceiver.openChapterPendingActivity(
                    context,
                    manga,
                    Notifications.ID_NEW_CHAPTERS,
                ),
            )
            // Download chapters action
            // Only add the action when chapters is within threshold
            if (chapters.size <= Downloader.CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
                addAction(
                    android.R.drawable.stat_sys_download_done,
                    context.stringResource(MR.strings.action_download),
                    NotificationReceiver.downloadChaptersPendingBroadcast(
                        context,
                        manga,
                        chapters,
                        Notifications.ID_NEW_CHAPTERS,
                    ),
                )
            }
        }.build()
    }

    /**
     * Cancels the progress notification.
     */
    fun cancelProgressNotification() {
        context.cancelNotification(Notifications.ID_LIBRARY_PROGRESS)
        context.cancelNotification(Notifications.ID_LIBRARY_SIZE_WARNING)
    }

    private suspend fun getMangaIcon(manga: Manga): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(manga)
            .transformations(CircleCropTransformation())
            .size(NOTIF_ICON_SIZE)
            .build()
        val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources)
        return drawable?.getBitmapOrNull()
    }

    private fun getNewChaptersDescription(chapters: Array<Chapter>): String {
        val displayableChapterNumbers = chapters
            .filter { it.isRecognizedNumber }
            .sortedBy { it.chapterNumber }
            .map { formatChapterNumber(it.chapterNumber) }
            .toSet()

        return when (displayableChapterNumbers.size) {
            // No sensible chapter numbers to show (i.e. no chapters have parsed chapter number)
            0 -> {
                // "1 new chapter" or "5 new chapters"
                context.pluralStringResource(
                    MR.plurals.notification_chapters_generic,
                    chapters.size,
                    chapters.size,
                )
            }
            // Only 1 chapter has a parsed chapter number
            1 -> {
                val remaining = chapters.size - displayableChapterNumbers.size
                if (remaining == 0) {
                    // "Chapter 2.5"
                    context.stringResource(
                        MR.strings.notification_chapters_single,
                        displayableChapterNumbers.first(),
                    )
                } else {
                    // "Chapter 2.5 and 10 more"
                    context.stringResource(
                        MR.strings.notification_chapters_single_and_more,
                        displayableChapterNumbers.first(),
                        remaining,
                    )
                }
            }
            // Everything else (i.e. multiple parsed chapter numbers)
            else -> {
                val shouldTruncate = displayableChapterNumbers.size > NOTIF_MAX_CHAPTERS
                if (shouldTruncate) {
                    // "Chapters 1, 2.5, 3, 4, 5 and 10 more"
                    val remaining = displayableChapterNumbers.size - NOTIF_MAX_CHAPTERS
                    val joinedChapterNumbers = displayableChapterNumbers
                        .take(NOTIF_MAX_CHAPTERS)
                        .joinToString(", ")
                    context.pluralStringResource(
                        MR.plurals.notification_chapters_multiple_and_more,
                        remaining,
                        joinedChapterNumbers,
                        remaining,
                    )
                } else {
                    // "Chapters 1, 2.5, 3"
                    context.stringResource(
                        MR.strings.notification_chapters_multiple,
                        displayableChapterNumbers.joinToString(", "),
                    )
                }
            }
        }
    }

    /**
     * Returns an intent to open the main activity.
     */
    private fun getNotificationIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Constants.SHORTCUT_UPDATES
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val HELP_WARNING_URL =
            "https://mangaro-web.vercel.app/"
    }
}

private const val NOTIF_MAX_CHAPTERS = 5
private const val NOTIF_TITLE_MAX_LEN = 45
private const val NOTIF_ICON_SIZE = 192
private const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60
