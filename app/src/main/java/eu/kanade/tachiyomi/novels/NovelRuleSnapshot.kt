package eu.kanade.tachiyomi.novels

/** A complete configuration composed only of whole authenticated source records. */
internal class NovelRuleSnapshot(
    private val key: ByteArray,
    private val trustedFull: ByteArray,
    envelopes: List<ByteArray> = emptyList(),
) {
    private val basePayload=NovelRuleProtocol.verifyEnvelope(trustedFull,key)
    private val base=NovelRules.decode(basePayload).also {require(it.mode=="FULL")}
    private val decoded=envelopes.map {envelope ->
        val copy=envelope.copyOf();copy to NovelRuleProtocol.verifyEnvelope(copy,key)
    }
    val revision: Int
    val effective: Map<String,Map<String,String>>
    val updates: List<ByteArray>
    private val latestPayload: ByteArray
    init {
        require(envelopes.size<=5)
        val revisions=decoded.map {NovelRules.validate(it.second).first}
        require(revisions.zipWithNext().all {(a,b)->a<b}) {"Ambiguous revision history"}
        val values=base.selectors.toMutableMap()
        val owners=mutableMapOf<String,Int>()
        var latest=basePayload
        decoded.forEachIndexed {index,(_,payload) ->
            val rule=NovelRules.decode(payload)
            if(rule.revision==base.revision) require(payload.contentEquals(basePayload)) {"Revision collision"}
            if(rule.revision>base.revision) {
                rule.selectors.forEach {(id,selectors)->values[id]=selectors;owners[id]=index}
                latest=payload
            }
        }
        updates=owners.values.toSet().sorted().map {decoded[it].first}
        require(updates.size<=4 && values.keys==NovelRules.domains.keys)
        effective=values.toMap()
        latestPayload=latest
        revision=NovelRules.validate(latest).first
    }
    fun updated(envelope: ByteArray): NovelRuleSnapshot {
        val payload=NovelRuleProtocol.verifyEnvelope(envelope,key)
        val next=NovelRules.validate(payload).first
        NovelRuleProtocol.requireIncreasing(next,revision)
        if(next==revision) {
            require(payload.contentEquals(latestPayload)) {"Revision reused with different payload"}
            return this
        }
        return NovelRuleSnapshot(key,trustedFull,updates+listOf(envelope))
    }
}
