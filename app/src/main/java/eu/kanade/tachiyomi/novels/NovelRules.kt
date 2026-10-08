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
    @Volatile private var selectors: Map<String,Map<String,String>> = emptyMap()
    fun selector(source: String,key: String,fallback: String) = selectors[source]?.get(key) ?: fallback
    fun apply(bytes: ByteArray) { selectors=validate(bytes).second }
    fun validate(bytes: ByteArray): Pair<Int,Map<String,Map<String,String>>> {
        require(bytes.size<=64*1024) {"Rules exceed bound"}
        val root=Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(root.keys==setOf("schemaVersion","engineVersion","revision","sources"))
        require(listOf("schemaVersion","engineVersion","revision").all {root[it]?.jsonPrimitive?.isString==false})
        require(root["schemaVersion"]?.jsonPrimitive?.int==1 && root["engineVersion"]?.jsonPrimitive?.int in 1..2)
        val revision=root["revision"]!!.jsonPrimitive.int.also {require(it>0)}
        val expected=mapOf("novel.kolnovel" to "kolnovel.com","novel.cenele" to "cenele.com",
            "novel.sunovels" to "sunovels.com","novel.seanovel" to "seanovel.org")
        val keys=setOf("catalogCards","catalogTitle","catalogCover","detailsTitle","detailsCover","description","genres","chapterLinks","chapterTitle","text","originalTitle")
        val list=root["sources"]!!.jsonArray
        require(list.size==4)
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
        require(result.keys==expected.keys)
        return revision to result
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
    private val publicKey=app.assets.open("novels/rules-public.der").use {it.readBytes()}
    private var revision=0
    @Synchronized fun load() {
        val bundled=app.assets.open("novels/rules-envelope.json").use {it.readBytes()}
        val bytes=NovelRuleProtocol.latestValid(listOfNotNull(read(current),read(previous),bundled),publicKey)
        revision=NovelRules.validate(bytes).first
        NovelRules.apply(bytes)
    }
    @Synchronized fun install(envelope: ByteArray) {
        val bytes=verified(envelope)
        val next=NovelRules.validate(bytes).first
        NovelRuleProtocol.requireIncreasing(next,revision)
        if(next==revision) return
        read(current)?.let { old -> if(runCatching {verified(old)}.isSuccess) write(previous,old) }
        write(current,envelope)
        NovelRules.apply(bytes);revision=next
    }
    private fun verified(envelope: ByteArray) = NovelRuleProtocol.verifyEnvelope(envelope,publicKey)
    private fun read(file: AtomicFile) = runCatching {file.openRead().use {it.readBytes()}}.getOrNull()
    private fun write(file: AtomicFile,bytes: ByteArray) {
        file.baseFile.parentFile?.mkdirs()
        val stream=file.startWrite()
        try {stream.write(bytes);file.finishWrite(stream)} catch(e: Exception) {file.failWrite(stream);throw e}
    }
}
