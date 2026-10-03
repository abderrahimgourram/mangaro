package eu.kanade.tachiyomi.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens/verifiers are encrypted at rest and excluded from Android backup. No credentials in logs. */
internal class AccountSessionStorage(context: Context, namespace: String) : SessionManager, CodeVerifierCache {
    private val directory = File(context.noBackupFilesDir, "account/$namespace")
    private val alias = "mangaro.account.$namespace"
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var verifierSaved = CompletableDeferred<Unit>()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(directory.exists() || directory.mkdirs())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            val file = AtomicFile(File(directory, name))
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        }
    }

    private suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = AtomicFile(File(directory, name))
            if (!file.baseFile.exists()) return@withLock null
            try {
                val bytes = file.openRead().use { it.readBytes() }
                require(bytes.size in 29..262144)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                }
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            } catch (_: Exception) {
                // Invalidated Keystore/partial or stale data means guest, never a startup crash.
                file.delete()
                null
            }
        }
    }

    private suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock { AtomicFile(File(directory, name)).delete() }
    }
    override suspend fun saveSession(session: UserSession) = write("session", json.encodeToString(session))
    override suspend fun loadSession(): UserSession = read("session")?.let {
        runCatching { json.decodeFromString<UserSession>(it) }.getOrNull()
    } ?: throw NoSessionFoundException()
    override suspend fun deleteSession() = delete("session")
    suspend fun beginAuthorization() { deleteCodeVerifier(); verifierSaved = CompletableDeferred() }
    suspend fun awaitVerifierSaved() { withTimeout(5_000) { verifierSaved.await() } }
    override suspend fun saveCodeVerifier(codeVerifier: String) {
        write("pkce", "${System.currentTimeMillis()}\n$codeVerifier")
        verifierSaved.complete(Unit)
    }
    override suspend fun loadCodeVerifier(): String? {
        val stored = read("pkce") ?: return null
        val created = stored.substringBefore('\n').toLongOrNull() ?: return null
        val age = System.currentTimeMillis() - created
        if (age !in 0..900_000) { deleteCodeVerifier(); return null }
        return stored.substringAfter('\n').takeIf { it.isNotBlank() }
    }
    override suspend fun deleteCodeVerifier() = delete("pkce")
}
