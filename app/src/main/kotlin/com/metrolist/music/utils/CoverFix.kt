/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.catalog.Catalog
import com.metrolist.music.playback.SpotifyMetadataRegistry
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.spotify.SpotifyMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers of catalog tracks that were given a made-up YouTube thumbnail: a track without its own
 * picture (album and playlist tracks often come without one) got "i.ytimg.com/vi/<id>/…" built from
 * its "mfb:" id, which is no video — the player showed an empty square. The real cover is the
 * catalog track's own.
 */
object CoverFix {
    private val found = ConcurrentHashMap<String, String>()

    /** A YouTube thumbnail address whose "video id" isn't one (it holds ':' — an "mfb:" id). */
    fun isBroken(url: String?): Boolean =
        url != null && url.contains("i.ytimg.com/vi/") && url.substringAfter("i.ytimg.com/vi/").substringBefore('/').contains(':')

    /** A catalog track ("mfb:" id) with no cover or a made-up one: its real cover is looked up. */
    fun needsLookup(mediaId: String, url: String?): Boolean =
        isBroken(url) || (url.isNullOrBlank() && FallbackIds.isFallbackId(mediaId))

    /** The cover [mediaId]'s track really has, or null when it can't be found. */
    suspend fun lookup(mediaId: String): String? {
        found[mediaId]?.let { return it }
        SpotifyMetadataRegistry.get(mediaId)?.let(SpotifyMapper::getTrackThumbnail)?.let {
            found[mediaId] = it
            return it
        }
        val catalogId = FallbackIds.catalogIdOf(mediaId) ?: SpotifyMetadataRegistry.get(mediaId)?.id ?: return null
        val track = Catalog.getTrack(catalogId).getOrNull() ?: return null
        return SpotifyMapper.getTrackThumbnail(track)?.also { found[mediaId] = it }
    }
}
