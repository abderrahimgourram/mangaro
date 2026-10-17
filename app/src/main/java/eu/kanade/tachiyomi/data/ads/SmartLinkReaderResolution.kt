package eu.kanade.tachiyomi.data.ads

/** A durable fulfilled page survives consumption of its temporary navigation row. */
internal object SmartLinkReaderResolution {
    fun verified(state: SmartLinkState, key: ReaderAdPageHost.Key, destination: String? = null): Boolean {
        val current = state.readerTransitions[adHash(key.owner, key.kind)]
        if (current != null) return current.owner == key.owner && current.id == key.boundary &&
            current.satisfied && (destination == null || current.destination == destination)
        return key.boundary in state.grantedReaderPages
    }
}
