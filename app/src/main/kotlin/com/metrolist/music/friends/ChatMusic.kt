/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import com.metrolist.music.catalog.Catalog
import com.metrolist.music.utils.AppLinks
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Music in a chat message: a track, album, playlist or artist, sent as what's needed to show its
 * card (title, who, cover, length) and to open or play it, never as a file.
 */
data class ChatMusic(
    val kind: AppLinks.Kind,
    /** The app link's id: a catalog id, or "yt:<video>" for YouTube. */
    val id: String,
    val title: String,
    val by: String,
    val cover: String? = null,
    val durationMs: Long = 0,
    /** Tracks in an album or playlist. */
    val count: Int = 0,
) {
    val isYouTube: Boolean get() = id.startsWith(AppLinks.YOUTUBE_PREFIX)

    fun link(): String = AppLinks.link(kind, id, title, by)

    /** The track to play, as friends' tracks are played (catalog id, else the YouTube video, else a search). */
    fun trackRef() = FriendsHub.TrackRef(
        title = title,
        artist = by,
        catalogId = id.takeUnless { isYouTube },
        mediaId = id.removePrefix(AppLinks.YOUTUBE_PREFIX).takeIf { isYouTube },
        cover = cover,
        durationMs = durationMs,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", kind.path)
        put("id", id)
        put("t", title)
        put("a", by)
        cover?.let { put("i", it) }
        if (durationMs > 0) put("d", durationMs)
        if (count > 0) put("n", count)
    }

    /** For the chat list and notifications. */
    fun label(): String = if (by.isNotBlank()) "$by — $title" else title

    companion object {
        fun fromJson(o: JSONObject): ChatMusic? = runCatching {
            ChatMusic(
                kind = AppLinks.Kind.entries.first { it.path == o.getString("k") },
                id = o.getString("id"),
                title = o.optString("t"),
                by = o.optString("a"),
                cover = o.optString("i").takeIf { it.startsWith("http") },
                durationMs = o.optLong("d"),
                count = o.optInt("n"),
            )
        }.getOrNull()

        fun fromJson(text: String?): ChatMusic? = text?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

        fun ofTrack(ref: FriendsHub.TrackRef): ChatMusic? {
            val id = ref.catalogId ?: ref.mediaId?.let { AppLinks.YOUTUBE_PREFIX + it } ?: return null
            return ChatMusic(AppLinks.Kind.TRACK, id, ref.title, ref.artist, ref.cover, ref.durationMs)
        }

        /**
         * A track the player knows by its media id: a catalog track ("mfb:<id>") or a YouTube video.
         * A file from the phone or a track found only on a source site (VK…) can't be sent: null.
         */
        fun ofMedia(mediaId: String, title: String, by: String, cover: String?, durationMs: Long): ChatMusic? {
            if (mediaId.startsWith("local:") || com.metrolist.music.resolver.SourceSearch.isSourceTrack(mediaId)) return null
            val id = com.metrolist.music.resolver.FallbackIds.catalogIdOf(mediaId) ?: (AppLinks.YOUTUBE_PREFIX + mediaId)
            return ChatMusic(AppLinks.Kind.TRACK, id, title, by, cover?.takeIf { it.startsWith("http") }, durationMs)
        }

        private val LINK = Regex("https?://\\S+")

        /** The first app link in [text] (drxwnify.pages.dev, Spotify, Yandex Music) and where it stands. */
        fun findLink(text: String): Pair<IntRange, AppLinks.Target>? =
            LINK.findAll(text).firstNotNullOfOrNull { m ->
                runCatching { AppLinks.parse(android.net.Uri.parse(m.value.trimEnd('.', ',', ')', '!', '?'))) }.getOrNull()
                    ?.let { m.range to it }
            }

        private fun pick(images: List<com.metrolist.spotify.models.SpotifyImage>): String? =
            (images.filter { (it.width ?: 0) in 200..400 }.minByOrNull { it.width ?: 0 } ?: images.firstOrNull())
                ?.url?.takeIf { it.startsWith("http") }

        /**
         * The card for a link: looked up in the catalog (cover, length, track count); when that
         * fails, what the link itself carries (its title and artist).
         */
        suspend fun resolve(target: AppLinks.Target): ChatMusic {
            val plain = ChatMusic(target.kind, target.id, target.title.orEmpty(), target.by.orEmpty())
            if (target.id.startsWith(AppLinks.YOUTUBE_PREFIX)) {
                val video = target.id.removePrefix(AppLinks.YOUTUBE_PREFIX)
                return if (target.kind == AppLinks.Kind.TRACK) plain.copy(cover = "https://i.ytimg.com/vi/$video/hqdefault.jpg") else plain
            }
            val found = withTimeoutOrNull(LOOKUP_MS) {
                runCatching {
                    Catalog.ensureAuthenticated()
                    when (target.kind) {
                        AppLinks.Kind.TRACK -> Catalog.getTrack(target.id).getOrNull()?.let { t ->
                            ofTrack(FriendsHub.refOfCatalog(t))?.copy(id = target.id)
                        }
                        AppLinks.Kind.ALBUM -> Catalog.album(target.id).getOrNull()?.let { a ->
                            plain.copy(title = a.name, by = a.artists.joinToString { it.name }, cover = pick(a.images), count = a.totalTracks)
                        }
                        AppLinks.Kind.PLAYLIST -> Catalog.playlist(target.id).getOrNull()?.let { p ->
                            plain.copy(title = p.name, by = p.owner?.displayName.orEmpty(), cover = pick(p.images), count = p.tracks?.total ?: 0)
                        }
                        AppLinks.Kind.ARTIST -> Catalog.artist(target.id).getOrNull()?.let { a ->
                            plain.copy(title = a.name, by = "", cover = pick(a.images))
                        }
                    }
                }.getOrNull()
            }
            return found?.takeIf { it.title.isNotBlank() } ?: plain
        }

        private const val LOOKUP_MS = 6_000L
    }
}
