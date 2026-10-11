package eu.kanade.tachiyomi.novels

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Signed declarative selectors only. No downloaded scripts, classes, endpoints or domain changes. */
internal object NovelRules {
    internal val domains=mapOf("novel.kolnovel" to "kolnovel.com","novel.cenele" to "cenele.com",
        "novel.sunovels" to "sunovels.com","novel.seanovel" to "seanovel.org")
    @Volatile private var selectors: Map<String,Map<String,String>> = emptyMap()
    fun selector(source: String,key: String,fallback: String) = selectors[source]?.get(key) ?: fallback
    internal fun snapshot() = selectors
    fun apply(bytes: ByteArray) { val rules=decode(bytes);require(rules.mode=="FULL");applyEffective(rules.selectors) }
    internal fun applyEffective(values: Map<String,Map<String,String>>) {
        require(values.keys==domains.keys)
        selectors=values.mapValues {it.value.toMap()}.toMap()
    }
    internal data class Decoded(val revision: Int,val mode: String,val selectors: Map<String,Map<String,String>>)
    fun validate(bytes: ByteArray): Pair<Int,Map<String,Map<String,String>>> {
        val decoded=decode(bytes);return decoded.revision to decoded.selectors
    }
    internal fun decode(bytes: ByteArray): Decoded {
        require(bytes.size<=64*1024) {"Rules exceed bound"}
        val root=Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence=true)).jsonObject
        val schema=root["schemaVersion"]!!.jsonPrimitive.int
        require(schema in 1..2)
        require(root.keys==if(schema==1) setOf("schemaVersion","engineVersion","revision","sources") else setOf("schemaVersion","engineVersion","revision","mode","sources"))
        require(listOf("schemaVersion","engineVersion","revision").all {root[it]?.jsonPrimitive?.isString==false})
        require(root["engineVersion"]!!.jsonPrimitive.int in if(schema==1) 1..2 else 3..3)
        val mode=if(schema==1) "FULL" else root["mode"]!!.jsonPrimitive.let {require(it.isString);it.content}
        require(mode in setOf("PARTIAL","FULL"))
        val revision=root["revision"]!!.jsonPrimitive.int.also {require(it>0)}
        val expected=domains
        val keys=setOf("catalogCards","catalogTitle","catalogCover","detailsTitle","detailsCover","description","genres","chapterLinks","chapterTitle","text","originalTitle")
        val list=root["sources"]!!.jsonArray
        require(if(mode=="FULL") list.size==4 else list.size in 1..4)
        val result=list.map { value ->
            val source=value.jsonObject
            require(source.keys==setOf("id","domain","selectors"))
            require(source["id"]!!.jsonPrimitive.isString && source["domain"]!!.jsonPrimitive.isString)
            val id=source.string("id");require(expected[id]==source.string("domain"))
            val values=source["selectors"]!!.jsonObject.mapValues { (key,selector) ->
                require(key in keys && selector.jsonPrimitive.isString)
                selector.jsonPrimitive.content.also { css ->
                    require(css.length in 1..240 && '<' !in css && '>' !in css && '\n' !in css && '\r' !in css && ':' !in css)
                    require(css.split(',').none {it.trim() in setOf("body","html","*",":root")})
                    Jsoup.parse("<p></p>").select(css) // Syntax validation; never evaluate JS/CSS.
                }
            }
            require("text" in values)
            id to values
        }.toMap()
        require(result.size==list.size) {"Duplicate source identity"}
        require(if(mode=="FULL") result.keys==expected.keys else expected.keys.containsAll(result.keys))
        return Decoded(revision,mode,result)
    }
}
internal object NovelRuleSignature {
    fun verify(bytes: ByteArray, signature: String, publicKey: ByteArray): Boolean = runCatching {
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance("SHA256withECDSA").run {initVerify(key);update(bytes);verify(Base64.getDecoder().decode(signature))}
    }.getOrDefault(false)
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}
/** Current and previous signed envelopes are separate from manga rules. Invalid candidates never replace either. */
internal class NovelRuleStore(context: Context) {
    private val app=context.applicationContext
    private val current=AtomicFile(File(app.filesDir,"novels-local/rules/current.json"))
    private val previous=AtomicFile(File(app.filesDir,"novels-local/rules/previous.json"))
    private val updates=AtomicFile(File(app.filesDir,"novels-local/rules/updates-v2.json"))
    private val priorUpdates=AtomicFile(File(app.filesDir,"novels-local/rules/updates-v2-previous.json"))
    private val publicKey=app.assets.open("novels/rules-public.der").use {it.readBytes()}
    fun load() = synchronized(lock) {NovelRules.applyEffective(snapshot().effective)}
    private fun snapshot(): NovelRuleSnapshot {
        val bundled=app.assets.open("novels/rules-envelope.json").use {it.readBytes()}
        val full=listOfNotNull(read(current),read(previous),bundled).filter {envelope ->
            runCatching {NovelRules.decode(verified(envelope)).mode=="FULL"}.getOrDefault(false)
        }.maxBy {NovelRules.validate(verified(it)).first}
        val base=NovelRuleSnapshot(publicKey,full)
        return listOfNotNull(read(updates),read(priorUpdates)).mapNotNull {raw ->
            runCatching {
                val root=Json.parseToJsonElement(raw.decodeToString(throwOnInvalidSequence=true)).jsonObject
                require(root.keys==setOf("cacheVersion","updates") && !root["cacheVersion"]!!.jsonPrimitive.isString && root["cacheVersion"]!!.jsonPrimitive.int==1)
                val entries=root["updates"]!!.jsonArray;require(entries.size in 1..4)
                val envelopes=entries.map {require(it.jsonPrimitive.isString);Base64.getDecoder().decode(it.jsonPrimitive.content)}
                NovelRuleSnapshot(publicKey,full,envelopes)
            }.getOrNull()
        }.plus(base).maxBy {it.revision}
    }
    fun install(envelope: ByteArray) = synchronized(lock) {
        val old=snapshot();val next=old.updated(envelope)
        if(next===old) return@synchronized
        if(old.updates.isNotEmpty()) write(priorUpdates,encoded(old))
        write(updates,encoded(next))
        NovelRules.applyEffective(next.effective)
    }
    fun install(manifest: ByteArray,envelope: ByteArray) {
        NovelRuleManifest.verify(manifest,publicKey).verifyEnvelope(envelope,publicKey)
        install(envelope)
    }
    private fun encoded(snapshot: NovelRuleSnapshot) = buildJsonObject {
        put("cacheVersion",1)
        put("updates",JsonArray(snapshot.updates.map {JsonPrimitive(Base64.getEncoder().encodeToString(it))}))
    }.toString().toByteArray()
    private fun verified(envelope: ByteArray) = NovelRuleProtocol.verifyEnvelope(envelope,publicKey)
    private fun read(file: AtomicFile) = runCatching {file.openRead().use {stream ->
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(true) {val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=768*1024);output.write(buffer,0,count)}
        output.toByteArray()
    }}.getOrNull()
    private fun write(file: AtomicFile,bytes: ByteArray) {
        file.baseFile.parentFile?.mkdirs()
        val stream=file.startWrite()
        try {stream.write(bytes);file.finishWrite(stream)} catch(e: Exception) {file.failWrite(stream);throw e}
    }
    companion object {private val lock=Any()}
}
