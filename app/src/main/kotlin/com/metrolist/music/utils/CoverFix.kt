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

    @Volatile
    private var database: com.metrolist.music.db.MusicDatabase? = null

    fun init(database: com.metrolist.music.db.MusicDatabase) {
        this.database = database
    }

    /**
     * A track with no cover or a made-up one: its real cover is looked up. A plain YouTube video id
     * counts too — a catalog track saved before covers were kept (opened from search while Spotify
     * refused the full lookup) sits in history and queues as that video with no picture at all.
     */
    fun needsLookup(mediaId: String, url: String?): Boolean =
        isBroken(url) || (url.isNullOrBlank() && !mediaId.startsWith("local:")) ||
            // A video frame (16:9, or 4:3 with bars) instead of the square artwork: looked up once.
            (url != null && "i.ytimg.com/vi/" in url && !found.containsKey(mediaId))

    /** The cover [mediaId]'s track really has, or null when it can't be found. */
    suspend fun lookup(mediaId: String): String? {
        found[mediaId]?.let { return it.ifBlank { null } }
        SpotifyMetadataRegistry.get(mediaId)?.let(SpotifyMapper::getTrackThumbnail)?.let {
            return remember(mediaId, it)
        }
        val catalogId = FallbackIds.catalogIdOf(mediaId)
            ?: SpotifyMetadataRegistry.get(mediaId)?.id
            ?: runCatching { database?.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId }.getOrNull()
        val cover = catalogId?.let { id ->
            Catalog.getTrack(id).getOrNull()?.let(SpotifyMapper::getTrackThumbnail) ?: spotifyOEmbedCover(id)
        } ?: catalogCoverOfRow(mediaId) ?: youtubeMusicArtwork(mediaId) ?: youtubeVideoCover(mediaId)
        android.util.Log.i("DrxwCover", "lookup $mediaId (catalog $catalogId) -> $cover")
        if (cover == null) found[mediaId] = "" // nothing better: not asked again
        return cover?.let { remember(mediaId, it) }
    }

    /** Keeps [cover] for [mediaId], in memory and on its song row, so lists show it as well. */
    private suspend fun remember(mediaId: String, cover: String): String {
        found[mediaId] = cover
        runCatching {
            val db = database ?: return@runCatching
            val song = db.getSongById(mediaId)?.song ?: return@runCatching
            val replaceable = song.thumbnailUrl.isNullOrBlank() || isBroken(song.thumbnailUrl) ||
                song.thumbnailUrl?.contains("i.ytimg.com/vi/") == true
            if (replaceable && song.thumbnailUrl != cover) db.query { update(song.copy(thumbnailUrl = cover)) }
        }
        return cover
    }

    /**
     * The catalog cover of a track known only by its saved row: its Spotify album when the row has
     * one, else the catalog track with the same title by the same artist. A track played as a
     * plain YouTube upload (a fan's re-upload, no artwork of its own) otherwise showed a video frame.
     */
    private suspend fun catalogCoverOfRow(mediaId: String): String? {
        val song = runCatching { database?.getSongById(mediaId) }.getOrNull() ?: return null
        song.song.albumId?.removePrefix(com.metrolist.music.utils.SPOTIFY_ID_PREFIX)
            ?.takeIf { song.song.albumId?.startsWith(com.metrolist.music.utils.SPOTIFY_ID_PREFIX) == true }
            ?.let { spotifyOEmbedCover(it, kind = "album") }
            ?.let { return it }
        val title = song.song.title.takeIf { it.isNotBlank() } ?: return null
        val artist = song.orderedArtists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return null
        if (!Catalog.ensureAuthenticated()) return null
        val tracks = Catalog.search("$artist $title", types = listOf("track"), limit = 5).getOrNull()?.tracks?.items.orEmpty()
        val match = tracks.firstOrNull { t ->
            SpotifyMapper.titlePlausiblyMatches(t.name, title) &&
                t.artists.any { it.name.equals(artist, ignoreCase = true) }
        } ?: return null
        return SpotifyMapper.getTrackThumbnail(match)
    }

    private val YOUTUBE_VIDEO_ID = Regex("[0-9A-Za-z_-]{11}")

    /**
     * The square album artwork YouTube Music shows for the video. A video thumbnail is a 16:9 frame:
     * the cover viewer then opened a grey band with the picture squeezed in the middle.
     */
    private suspend fun youtubeMusicArtwork(mediaId: String): String? {
        if (!YOUTUBE_VIDEO_ID.matches(mediaId)) return null
        val thumbnail = com.metrolist.innertube.YouTube.queue(listOf(mediaId)).getOrNull()?.firstOrNull()?.thumbnail
            ?: return null
        return thumbnail.takeIf { "googleusercontent.com" in it || "ggpht.com" in it }
            ?.let { CoverSaver.largest(it) }
    }

    /** The video's own picture, for a YouTube track no catalog knows. */
    private fun youtubeVideoCover(mediaId: String): String? =
        mediaId.takeIf { YOUTUBE_VIDEO_ID.matches(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val SPOTIFY_TRACK_ID = Regex("[0-9A-Za-z]{22}")

    /**
     * Spotify's public oEmbed: the cover of any track without a token. The full track lookup is
     * often refused (rate limit), which left tracks queued from search without a cover for good.
     */
    private fun spotifyOEmbedCover(catalogId: String, kind: String = "track"): String? {
        if (!SPOTIFY_TRACK_ID.matches(catalogId)) return null
        return runCatching {
            val request = okhttp3.Request.Builder()
                .url("https://open.spotify.com/oembed?url=spotify:$kind:$catalogId")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                org.json.JSONObject(response.body?.string().orEmpty()).optString("thumbnail_url")
                    .takeIf { it.startsWith("http") }
                    // The 640 px variant of the 300 px cover oEmbed names.
                    ?.replace("ab67616d00001e02", "ab67616d0000b273")
            }
        }.getOrNull()
    }
}
