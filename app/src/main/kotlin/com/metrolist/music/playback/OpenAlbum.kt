/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.playback.queues.Queue

/**
 * The album screen that is open right now. A track queued by hand while its album is open (from
 * a menu, a swipe, a selection — whichever way) remembers the album: when the queue runs out on
 * it, the album goes on from after that track instead of recommendations.
 */
object OpenAlbum {
    @Volatile private var owner: Any? = null
    @Volatile private var queueFrom: ((String) -> Queue?)? = null

    /** [queueFrom]: the album as a queue starting at the player item with this media id, or null when it isn't on the album. */
    fun open(owner: Any, queueFrom: (String) -> Queue?) {
        this.owner = owner
        this.queueFrom = queueFrom
    }

    fun close(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            queueFrom = null
        }
    }

    fun queueFrom(mediaId: String): Queue? = runCatching { queueFrom?.invoke(mediaId) }.getOrNull()
}
