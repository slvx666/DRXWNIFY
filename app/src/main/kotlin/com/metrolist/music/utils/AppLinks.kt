/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.Uri
import com.metrolist.music.catalog.Catalog
import com.metrolist.yandex.YandexIds

/**
 * Links that open an album, playlist, track or artist in the app:
 * `https://drxwnify.pages.dev/album/<id>?n=<title>&by=<artist>`. The site answers a phone without
 * the app with a page (cover, title, "open in the app", download); with the app installed Android
 * opens it straight away (app links, verified through the site's assetlinks.json).
 *
 * Ids are the catalog's (Spotify, or Yandex with its "ym_" prefix); YouTube things carry "yt:".
 * The title and artist ride along so a link from a catalog the receiver can't open is still found
 * by a search. Spotify's own links (open.spotify.com/…) shared to the app open the same way.
 */
object AppLinks {
    const val SITE = "https://drxwnify.pages.dev"
    const val YOUTUBE_PREFIX = "yt:"

    enum class Kind(val path: String) { ALBUM("album"), PLAYLIST("playlist"), TRACK("track"), ARTIST("artist") }

    data class Target(val kind: Kind, val id: String, val title: String?, val by: String?)

    fun link(kind: Kind, id: String, title: String? = null, by: String? = null): String {
        val query = listOfNotNull(
            title?.takeIf { it.isNotBlank() }?.let { "n=" + Uri.encode(it.take(80)) },
            by?.takeIf { it.isNotBlank() }?.let { "by=" + Uri.encode(it.take(60)) },
        )
        return "$SITE/${kind.path}/" + Uri.encode(id) + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }

    /** What goes into a share: "Artist — Title" and the link. */
    fun shareText(kind: Kind, id: String, title: String?, by: String?): String =
        listOfNotNull(
            title?.takeIf { it.isNotBlank() }?.let { t -> if (!by.isNullOrBlank()) "$by — $t" else t },
            link(kind, id, title, by),
        ).joinToString("\n")

    /** A track's link from its player id: a catalog track ("mfb:<id>") or a YouTube video. */
    fun trackLink(mediaId: String, title: String?, by: String?): String? {
        val catalogId = com.metrolist.music.resolver.FallbackIds.catalogIdOf(mediaId)
        val id = when {
            catalogId != null -> catalogId
            mediaId.startsWith("local:") -> return null
            else -> YOUTUBE_PREFIX + mediaId
        }
        return shareText(Kind.TRACK, id, title, by)
    }

    fun parse(uri: Uri): Target? {
        val segments = uri.pathSegments.orEmpty()
        val (kindName, id) = when {
            (uri.scheme == "https" || uri.scheme == "http") && uri.host == "drxwnify.pages.dev" ->
                (segments.getOrNull(0) ?: return null) to (segments.getOrNull(1) ?: return null)
            uri.scheme == "drxwnify" ->
                (uri.host ?: return null) to (segments.getOrNull(0) ?: return null)
            uri.host == "open.spotify.com" -> {
                // open.spotify.com/intl-de/album/<id>
                val rest = segments.dropWhile { it.startsWith("intl-") }
                (rest.getOrNull(0) ?: return null) to (rest.getOrNull(1) ?: return null)
            }
            else -> return null
        }
        val kind = Kind.entries.firstOrNull { it.path == kindName } ?: return null
        if (id.isBlank()) return null
        return Target(kind, id, uri.getQueryParameter("n"), uri.getQueryParameter("by"))
    }

    /**
     * A Yandex id the receiver may not be able to open (another account, or none): checked, and
     * when it fails the thing is looked up by its title and artist in the receiver's catalog.
     */
    suspend fun reachableId(target: Target): String {
        if (!YandexIds.isYandex(target.id) || Catalog.isYandexSource) return target.id
        val opens = when (target.kind) {
            Kind.ALBUM -> Catalog.album(target.id).isSuccess
            Kind.PLAYLIST -> Catalog.playlist(target.id).isSuccess
            Kind.TRACK -> Catalog.getTrack(target.id).isSuccess
            Kind.ARTIST -> Catalog.artist(target.id).isSuccess
        }
        if (opens) return target.id
        val query = listOfNotNull(target.by, target.title).joinToString(" ").ifBlank { return target.id }
        val result = Catalog.search(query, types = listOf(target.kind.path), limit = 1).getOrNull() ?: return target.id
        return when (target.kind) {
            Kind.ALBUM -> result.albums?.items?.firstOrNull()?.id
            Kind.PLAYLIST -> result.playlists?.items?.firstOrNull()?.id
            Kind.TRACK -> result.tracks?.items?.firstOrNull()?.id
            Kind.ARTIST -> result.artists?.items?.firstOrNull()?.id
        } ?: target.id
    }
}
