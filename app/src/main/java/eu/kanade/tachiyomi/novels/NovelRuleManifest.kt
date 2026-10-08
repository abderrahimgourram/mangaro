package eu.kanade.tachiyomi.novels

import kotlinx.serialization.json.*
import java.util.Base64

/** Signed, bounded declarative update protocol. Independent of the manga publisher and APK updater. */
internal data class NovelRuleManifest(val engineVersion: Int, val revision: Int, val envelopePath: String, val sha256: String) {
    fun verifyEnvelope(envelope: ByteArray, key: ByteArray): ByteArray {
        require(NovelRuleSignature.sha256(envelope) == sha256) { "Envelope hash mismatch" }
        val payload = NovelRuleProtocol.verifyEnvelope(envelope, key)
        val root = Json.parseToJsonElement(payload.decodeToString()).jsonObject
        require(root["engineVersion"]!!.jsonPrimitive.int == engineVersion && NovelRules.validate(payload).first == revision) {
            "Signed manifest and rules disagree"
        }
        return payload
    }

    companion object {
        fun verify(raw: ByteArray, key: ByteArray): NovelRuleManifest {
            require(raw.size <= 8192)
            val data = Json.parseToJsonElement(raw.decodeToString(throwOnInvalidSequence = true)).jsonObject
            require(data.keys == setOf("schemaVersion", "engineVersion", "revision", "envelopePath", "sha256", "signature"))
            fun number(name: String): Int = data[name]!!.jsonPrimitive.let { require(!it.isString); it.int }
            fun text(name: String): String = data[name]!!.jsonPrimitive.let { require(it.isString); it.content }
            require(number("schemaVersion") == 1)
            val engine = number("engineVersion").also { require(it in 1..2) }
            val revision = number("revision").also { require(it > 0) }
            val path = text("envelopePath").also { require(it == "published/rules-$revision.json") }
            val hash = text("sha256").also { require(Regex("[0-9a-f]{64}").matches(it)) }
            val signed = "MangaroNovelManifestV1\n1\n$engine\n$revision\n$path\n$hash\n".toByteArray(Charsets.US_ASCII)
            require(NovelRuleSignature.verify(signed, text("signature"), key)) { "Invalid manifest signature" }
            return NovelRuleManifest(engine, revision, path, hash)
        }
    }
}

internal object NovelRuleProtocol {
    fun verifyEnvelope(envelope: ByteArray, publicKey: ByteArray): ByteArray {
        require(envelope.size <= 96 * 1024)
        val data = Json.parseToJsonElement(envelope.decodeToString(throwOnInvalidSequence = true)).jsonObject
        require(data.keys == setOf("payload", "sha256", "signature"))
        require(data.values.all { it.jsonPrimitive.isString })
        val bytes = Base64.getDecoder().decode(data.string("payload"))
        require(NovelRuleSignature.sha256(bytes) == data.string("sha256"))
        require(NovelRuleSignature.verify(bytes, data.string("signature"), publicKey))
        NovelRules.validate(bytes)
        return bytes
    }

    /** Corrupt/incompatible downloads never shadow newer packaged or last-known-good rules. */
    fun latestValid(candidates: List<ByteArray>, key: ByteArray): ByteArray = candidates.mapNotNull { envelope ->
        runCatching { verifyEnvelope(envelope, key) }.getOrNull()
    }.maxBy { NovelRules.validate(it).first }

    fun requireIncreasing(candidate: Int, current: Int) { require(candidate >= current) { "Rules rollback rejected" } }
}
