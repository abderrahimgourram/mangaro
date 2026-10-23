package eu.kanade.tachiyomi.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/** Opt-in public metadata only. No default logging, bodies, library or account fields. */
internal object WorkIdentityDiagnostics {
    /** Explicit private-file opt-in on a disposable emulator only; disabled on ordinary devices. */
    fun installEmulatorObserver(context: android.content.Context) {
        if (android.os.Build.HARDWARE !in setOf("ranchu", "goldfish") ||
            !context.getFileStreamPath("search-public-metadata-diagnostics.enabled").isFile
        ) {
            return
        }
        observer = { snapshot ->
            snapshot.chunked(3000).forEachIndexed { index, chunk ->
                android.util.Log.d("MangaroSearchPublic", "$index:$chunk")
            }
        }
    }

    @Volatile var observer: ((String) -> Unit)? = null

    fun observe(
        query: String,
        candidates: List<SearchWorkCandidate>,
        groups: List<SearchWorkGroup>,
    ) {
        val sink = observer ?: return
        runCatching { sink(snapshot(query, candidates, groups)) }
    }

    private fun safe(value: String) = value.filterNot { it.isISOControl() }.take(512)

    private fun route(value: String): String =
        runCatching {
            val uri = URI(if (value.startsWith("novel.")) value.substringAfter(':') else value)
            if (uri.rawUserInfo != null) return@runCatching "[redacted]"
            if (uri.isAbsolute && uri.scheme !in setOf("https", "http")) return@runCatching "[opaque-edition]"
            URI(uri.scheme, uri.authority, uri.path, null, null).toString().let(::safe)
        }.getOrDefault("[opaque-edition]")

    fun snapshot(
        query: String,
        candidates: List<SearchWorkCandidate>,
        groups: List<SearchWorkGroup>,
    ): String {
        val byId = groups.flatMap { g -> g.members.map { it.id to g.key } }.toMap()
        val explanations = groups.flatMap { g -> g.members.map { it.id to g.identityDecision } }.toMap()
        val resolver = WorkIdentityResolverV2()
        return buildJsonObject {
            put("schema", 3)
            put("query", safe(query))
            put("candidateCount", candidates.size)
            put("visibleCardCount", groups.size)
            put(
                "rows",
                buildJsonArray {
                    candidates.take(100).forEach { c ->
                        add(
                            buildJsonObject {
                                put(
                                    "source",
                                    c.source.takeIf {
                                        it.matches(Regex("[A-Za-z0-9_.-]{1,80}"))
                                    } ?: "[redacted-source]",
                                )
                                put("editionReference", route(c.catalogIdentity?.route ?: c.id))
                                put("title", safe(c.title))
                                put("normalizedTitle", WorkIdentityNormalizer.title(c.title, c.kind))
                                put("kind", c.kind.name)
                                val reference = c.catalogIdentity?.route ?: c.id.substringAfter(':', c.id)
                                val classification = tachiyomi.domain.manga.model.WorkCandidateClassification.classify(
                                    reference,
                                )
                                put("candidateType", classification.kind.name)
                                put("candidateTypeReason", classification.reason)
                                val selected = groups.firstOrNull { group ->
                                    group.members.any { it.id == c.id }
                                }?.winner
                                fun quality(
                                    row: SearchWorkCandidate,
                                ) = tachiyomi.domain.manga.service.CatalogEditionEvidence(
                                    indexedChapters = row.chapters.distinct,
                                    complete = row.chapters.complete,
                                    readable = row.chapters.readable,
                                    indexUsable = row.chapters.indexUsable,
                                    uncertain = row.chapters.indexUncertain,
                                )
                                put(
                                    "editionSelectionReason",
                                    selected?.let {
                                        tachiyomi.domain.manga.service.CatalogEditionSelection.reason(
                                            quality(it),
                                            quality(c),
                                        )
                                    } ?: "no-group",
                                )

                                put("originalTitle", c.originalTitle?.let(::safe)?.let(::JsonPrimitive) ?: JsonNull)
                                put("aliases", JsonArray(c.aliases.take(16).map { JsonPrimitive(safe(it)) }))
                                put(
                                    "externalIds",
                                    buildJsonObject {
                                        c.externalIds
                                            .filterKeys {
                                                it in setOf(
                                                    "anilist",
                                                    "myanimelist",
                                                    "mangadex",
                                                    "mangaupdates",
                                                    "kitsu",
                                                    "novelupdates",
                                                )
                                            }.forEach { (k, v) -> put(safe(k), safe(v)) }
                                    },
                                )
                                put("creator", c.author?.let(::safe)?.let(::JsonPrimitive) ?: JsonNull)
                                put("artist", c.artist?.let(::safe)?.let(::JsonPrimitive) ?: JsonNull)
                                put("format", c.format?.let(::safe)?.let(::JsonPrimitive) ?: JsonNull)
                                put("year", c.year?.let(::JsonPrimitive) ?: JsonNull)
                                put("uniqueChapterCount", c.chapters.distinct?.let(::JsonPrimitive) ?: JsonNull)
                                put("indexComplete", c.chapters.complete)
                                put("queryMatchRank", c.queryMatchRank)
                                put("readerUsable", c.chapters.readable?.let(::JsonPrimitive) ?: JsonNull)
                                put("indexUsable", c.chapters.indexUsable?.let(::JsonPrimitive) ?: JsonNull)
                                put("indexUncertain", c.chapters.indexUncertain)
                                put("selected", groups.any { it.winner.id == c.id })
                                put("edition", c.edition?.let(::safe)?.let(::JsonPrimitive) ?: JsonNull)
                                put("metadataOrigin", safe(c.metadataOrigin))
                                put(
                                    "group",
                                    java.security.MessageDigest
                                        .getInstance("SHA-256")
                                        .digest(byId[c.id].orEmpty().toByteArray())
                                        .joinToString("") { "%02x".format(it) },
                                )
                                put("groupDecision", explanations[c.id]?.verdict?.name.orEmpty())
                                put("groupReason", explanations[c.id]?.reason.orEmpty())
                                put(
                                    "reviewedWork",
                                    c.catalogIdentity?.reviewedWorkKey?.let(::JsonPrimitive) ?: JsonNull,
                                )
                            },
                        )
                    }
                },
            )
            put(
                "candidateDecisions",
                buildJsonArray {
                    val buckets =
                        candidates
                            .take(100)
                            .flatMap { c ->
                                WorkIdentityNormalizer.names(c).map { it to c }
                            }.groupBy({ it.first }, { it.second })
                    var budget = 30
                    val seen = mutableSetOf<Set<String>>()
                    buckets.values.forEach { rows ->
                        for (i in rows.indices) {
                            for (j in i + 1 until rows.size) {
                                if (budget == 0) break
                                val a = rows[i]
                                val b = rows[j]
                                if (!seen.add(setOf(a.id, b.id))) continue
                                val d = resolver.decide(a, b)
                                budget--
                                val ak = AtlasCandidateIndex.keys(resolver.evidence(a, null))
                                val bk = AtlasCandidateIndex.keys(resolver.evidence(b, null))
                                val families =
                                    ak
                                        .intersect(bk)
                                        .map { it.substringBefore(':') }
                                        .distinct()
                                        .sorted()
                                add(
                                    buildJsonObject {
                                        put("left", route(a.catalogIdentity?.route ?: a.id))
                                        put(
                                            "right",
                                            route(b.catalogIdentity?.route ?: b.id),
                                        )
                                        put("decision", d.verdict.name)
                                        put("reason", d.reason)
                                        put("retrievableByIndex", families.isNotEmpty())
                                        put("sharedBlockingFamilies", JsonArray(families.map(::JsonPrimitive)))
                                        put(
                                            "missingEvidence",
                                            buildJsonArray {
                                                if (a.author.isNullOrBlank() || b.author.isNullOrBlank()) add("creator")
                                                if (a.originalTitle.isNullOrBlank() ||
                                                    b.originalTitle.isNullOrBlank()
                                                ) {
                                                    add("original-title")
                                                }
                                                if (!AtlasCandidateIndex.authoritative(
                                                        a,
                                                    ) || !AtlasCandidateIndex.authoritative(b)
                                                ) {
                                                    add("authoritative-crosswalk")
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }.toString()
    }
}
