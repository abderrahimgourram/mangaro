package tachiyomi.domain.storage.service

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import java.io.File

class StorageManager(
    private val context: Context,
    storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val legacyDownloadsBase = storagePreferences.legacyDownloadsBaseDirectory.apply {
        if (!isSet()) set(storagePreferences.baseStorageDirectory.get())
    }

    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory.get())

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        val root = baseDir?.createDirectory(AUTOMATIC_BACKUPS_PATH)
        val owner = tachiyomi.core.common.util.lang.LocalAccountScope.segment()
        return if (owner.isEmpty()) root else root?.createDirectory("accounts")?.createDirectory(owner)
    }

    private fun downloadRoot(owner: String): UniFile? {
        val external = context.getExternalFilesDir(null) ?: return null
        val segment = tachiyomi.core.common.util.lang.LocalAccountScope.segment(owner)
        val directory = File(external, if (segment.isEmpty()) DOWNLOADS_PATH else "$DOWNLOADS_PATH/accounts/$segment")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        return UniFile.fromFile(directory).also { DiskUtil.createNoMediaFile(it, context) }
    }
    fun getDownloadsDirectory(owner: String = tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()): UniFile? = downloadRoot(owner)

    /** Only explicit ownership claims expose a retained guest namespace; files never move. */
    fun getDownloadDirectories(): List<UniFile> = tachiyomi.core.common.util.lang.LocalAccountScope.readableFileOwners().flatMap { owner ->
        listOfNotNull(downloadRoot(owner), if (owner == "guest:0") getBaseDir(legacyDownloadsBase.get())?.findFile(DOWNLOADS_PATH) else null)
    }.distinctBy { it.uri }

    fun getLocalSourceDirectory(): UniFile? {
        return baseDir?.createDirectory(LOCAL_SOURCE_PATH)
    }
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"
