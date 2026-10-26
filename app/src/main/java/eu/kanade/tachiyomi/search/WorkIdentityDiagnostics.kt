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
        traceObserver = { event ->
            event.chunked(3000).forEachIndexed { index, chunk ->
                android.util.Log.d("MangaroIdentityTrace", "$index:$chunk")
            }
        }
    }

    @Volatile var observer: ((String) -> Unit)? = null

    @Volatile var traceObserver: ((String) -> Unit)? = null

    /** Actual comparison/attachment events, bounded by the resolver; public edition metadata only. */
    fun comparison(stage: String, a: SearchWorkCandidate, b: SearchWorkCandidate, decision: WorkIdentityDecision) {
        val sink = traceObserver ?: return
        runCatching {
            sink(
                buildJsonObject {
                    put("stage", stage)
                    put("leftSource", publicSource(a.source))
                    put("leftRoute", route(a.catalogIdentity?.route ?: a.id))
                    put("rightSource", publicSource(b.source))
                    put("rightRoute", route(b.catalogIdentity?.route ?: b.id))
                    put("decision", decision.verdict.name)
                    put("reason", decision.reason)
                }.toString(),
            )
        }
    }

    fun groups(stage: String, groups: Map<String, List<SearchWorkCandidate>>) {
        val sink = traceObserver ?: return
        runCatching {
            sink(
                buildJsonObject {
                    put("stage", stage)
                    put(
                        "groups",
                        buildJsonArray {
                            groups.entries.take(100).forEach { (key, members) ->
                                add(
                                    buildJsonObject {
                                        put("keyDigest", digest(key))
                                        put(
                                            "members",
                                            buildJsonArray {
                                                members.take(32).forEach { c ->
                                                    add(
                                                        buildJsonObject {
                                                            put("source", publicSource(c.source))
                                                            put("route", route(c.catalogIdentity?.route ?: c.id))
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
            )
        }
    }

    fun cards(rows: List<eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult>) {
        val sink = traceObserver ?: return
        runCatching {
            sink(
                buildJsonObject {
                    put("stage", "search-ui-projection")
                    put(
                        "cards",
                        buildJsonArray {
                            rows.take(100).forEach { row ->
                                add(
                                    buildJsonObject {
                                        put("keyDigest", digest(row.workKey.orEmpty()))
                                        put("source", row.manga.source.toString())
                                        put("route", route(row.manga.url))
                                        put("alternativeCount", row.alternatives.size)
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
            )
        }
    }

    fun navigation(key: String?, source: String, destination: String) {
        val sink = traceObserver ?: return
        runCatching {
            sink(
                buildJsonObject {
                    put("stage", "actual-card-click")
                    put("keyDigest", digest(key.orEmpty()))
                    put("source", source)
                    put("route", route(destination))
                }.toString(),
            )
        }
    }

    fun observe(
        query: String,
        candidates: List<SearchWorkCandidate>,
        groups: List<SearchWorkGroup>,
    ) {
        val sink = observer ?: return
        runCatching { sink(snapshot(query, candidates, groups)) }
    }

    private fun safe(value: String) = value.filterNot { it.isISOControl() }.take(512)
    private fun publicSource(value: String) = value.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
        ?: "[redacted-source]"
    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

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
            put("schema", 4)
            put("query", safe(query))
            put("candidateCount", candidates.size)
            put("groupCount", groups.size)
            put("visibleCardCount", groups.count { g -> (g.members.minOf { it.queryMatchRank } ushr 32) < 7 })
            put("additionalGroupCount", groups.count { g -> (g.members.minOf { it.queryMatchRank } ushr 32) >= 7 })
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
                                put("canonicalMedium", c.kind.name)
                                put(
                                    "declaredFormatMedium",
                                    tachiyomi.domain.manga.service.CatalogFormatCompatibility.describe(
                                        c.format,
                                    ).medium.name,
                                )
                                put("cardKeyDigest", digest(byId[c.id].orEmpty()))
                                put("cacheEditionKeyDigest", digest(c.catalogIdentity?.editionKey.orEmpty()))
                                val reference = c.catalogIdentity?.route ?: c.id.substringAfter(':', c.id)
                                val classification = tachiyomi.domain.manga.model.WorkCandidateClassification.classify(
                                    reference,
                                )
                                put("candidateType", classification.kind.name)
                                put("presentationState", c.presentationState.name)
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
                                put("queryMatchReason", safe(c.queryMatchReason))
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
                                val presentation = resolver.presentationDecision(
                                    resolver.evidence(a, null),
                                    resolver.evidence(b, null),
                                )
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
                                        put("presentationDecision", presentation.verdict.name)
                                        put("presentationReason", presentation.reason)
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
