package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download

/** A restored/failed queue must not block a new request; a deliberate pause stays respected. */
internal fun shouldAutoStartDownloads(autoStart: Boolean, paused: Boolean, running: Boolean, states: List<Download.State>): Boolean =
    autoStart && (states.isEmpty() || (!paused && !running))
