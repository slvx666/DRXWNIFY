package com.metrolist.yandex

import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyImage
import com.metrolist.spotify.models.SpotifyLibraryEntry
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistOwner
import com.metrolist.spotify.models.SpotifyPlaylistTracksRef
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Ids of Yandex Music entities inside Meld. Everything downstream (routes, queues, the match cache,
 * the player registry) works with catalog ids that are plain strings, so a Yandex entity is simply a
 * `ym_`-prefixed id. Spotify ids are 22-char base62 and never contain `_`, so the prefix is unambiguous.
 */
object YandexIds {
    const val PREFIX = "ym_"
    const val LIKED_SONGS_ID = "${PREFIX}liked"

    fun isYandex(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    fun raw(id: String): String = id.removePrefix(PREFIX)

    fun track(rawId: String) = "$PREFIX$rawId"
    fun album(rawId: String) = "$PREFIX$rawId"
    fun artist(rawId: String) = "$PREFIX$rawId"

    /** Playlists are addressed by owner uid + kind: `ym_<uid>_<kind>`. */
    fun playlist(ownerUid: String, kind: String) = "$PREFIX${ownerUid}_$kind"

    /** Splits a playlist id into (ownerUid, kind), or null when it is not a Yandex playlist id. */
    fun parsePlaylist(id: String): Pair<String, String>? {
        if (!isYandex(id)) return null
        val raw = raw(id)
        val sep = raw.lastIndexOf('_')
        if (sep <= 0 || sep == raw.lastIndex) return null
        return raw.substring(0, sep) to raw.substring(sep + 1)
    }
}

/**
 * Pure JSON → catalog-model mapping for the (unofficial) api.music.yandex.net payloads. Kept free of
 * networking so the mapping can be unit-tested against captured responses.
 */
object YandexParser {

    // ── JSON helpers (the API mixes numbers and strings for ids) ────────────────────────────────

    internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

    internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    internal fun JsonObject.str(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    internal fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    internal fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    /** "avatars.yandex.net/get-music-content/…/%%" → absolute https URL of the requested size. */
    fun coverUrl(uri: String?, size: Int = 400): String? {
        val u = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sized = u.replace("%%", "${size}x$size")
        return if (sized.startsWith("http")) sized else "https://$sized"
    }

    private fun coverImages(uri: String?): List<SpotifyImage> {
        if (uri.isNullOrBlank()) return emptyList()
        // Largest first, like Spotify's image lists; list tiles pick the 200..400 variant.
        return listOfNotNull(
            coverUrl(uri, 1000)?.let { SpotifyImage(it, 1000, 1000) },
            coverUrl(uri, 400)?.let { SpotifyImage(it, 400, 400) },
            coverUrl(uri, 200)?.let { SpotifyImage(it, 200, 200) },
        )
    }

    /** Yandex album "type" → Spotify-style lower-case album type. */
    fun albumType(type: String?, trackCount: Int?): String = when (type?.lowercase()) {
        "single" -> if ((trackCount ?: 1) > 3) "ep" else "single"
        "compilation" -> "compilation"
        else -> "album"
    }

    // ── Entities ─────────────────────────────────────────────────────────────────────────────────

    fun artist(obj: JsonObject): SpotifySimpleArtist? {
        val id = obj.str("id") ?: return null
        return SpotifySimpleArtist(
            id = YandexIds.artist(id),
            name = obj.str("name").orEmpty(),
            uri = "yandexmusic:artist:$id",
        )
    }

    fun fullArtist(obj: JsonObject): SpotifyArtist? {
        val id = obj.str("id") ?: return null
        val cover = obj.obj("cover")?.let { it.str("uri") ?: it.str("itemsUri") } ?: obj.str("ogImage")
        return SpotifyArtist(
            id = YandexIds.artist(id),
            name = obj.str("name").orEmpty(),
            images = coverImages(cover),
            genres = obj.arr("genres")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            uri = "yandexmusic:artist:$id",
        )
    }

    fun simpleAlbum(obj: JsonObject): SpotifySimpleAlbum? {
        val id = obj.str("id") ?: return null
        val trackCount = obj.long("trackCount")?.toInt()
        return SpotifySimpleAlbum(
            id = YandexIds.album(id),
            name = obj.str("title").orEmpty(),
            images = coverImages(obj.str("coverUri") ?: obj.str("ogImage")),
            releaseDate = obj.str("releaseDate")?.take(10) ?: obj.long("year")?.toString(),
            albumType = albumType(obj.str("type"), trackCount),
            artists = obj.arr("artists")?.mapNotNull { it.objOrNull()?.let(::artist) }.orEmpty(),
            uri = "yandexmusic:album:$id",
        )
    }

    /**
     * Maps a Yandex track. [fallbackAlbum] is used for tracks nested inside an album payload whose own
     * `albums` array is missing. Unavailable tracks (no rights in the region) are still returned — the
     * audio fallback layer does not need Yandex's own stream, only its metadata.
     */
    fun track(obj: JsonObject, fallbackAlbum: SpotifySimpleAlbum? = null): SpotifyTrack? {
        val id = obj.str("id") ?: obj.str("realId") ?: return null
        val title = obj.str("title").orEmpty()
        val version = obj.str("version")
        val name = if (version != null && !title.contains(version, ignoreCase = true)) "$title ($version)" else title
        val albumObj = obj.arr("albums")?.firstOrNull()?.objOrNull()
        val album = albumObj?.let(::simpleAlbum)
            ?.let { a -> if (a.images.isEmpty()) a.copy(images = coverImages(obj.str("coverUri"))) else a }
            ?: fallbackAlbum
        val trackNumber = albumObj?.obj("trackPosition")?.long("index")?.toInt()
        return SpotifyTrack(
            id = YandexIds.track(id),
            name = name,
            artists = obj.arr("artists")?.mapNotNull { it.objOrNull()?.let(::artist) }.orEmpty(),
            album = album,
            durationMs = (obj.long("durationMs") ?: 0L).toInt(),
            explicit = obj.str("contentWarning").equals("explicit", ignoreCase = true),
            trackNumber = trackNumber,
            uri = "yandexmusic:track:$id",
        )
    }

    fun album(obj: JsonObject): SpotifyAlbum? {
        val simple = simpleAlbum(obj) ?: return null
        val tracks = obj.arr("volumes")
            ?.flatMap { vol -> (vol as? JsonArray).orEmpty().mapNotNull { it.objOrNull() } }
            ?.mapIndexedNotNull { index, t ->
                track(t, fallbackAlbum = simple)?.let { tr ->
                    // Inside an album payload the album the user opened is authoritative.
                    tr.copy(album = simple, trackNumber = tr.trackNumber ?: (index + 1))
                }
            }
            .orEmpty()
        return SpotifyAlbum(
            id = simple.id,
            name = simple.name,
            albumType = simple.albumType,
            artists = simple.artists,
            images = simple.images,
            releaseDate = simple.releaseDate,
            totalTracks = obj.long("trackCount")?.toInt() ?: tracks.size,
            tracks = if (tracks.isEmpty()) null else SpotifyPaging(items = tracks, total = tracks.size, limit = tracks.size),
            uri = simple.uri,
            genres = listOfNotNull(obj.str("genre")),
        )
    }

    fun playlist(obj: JsonObject): SpotifyPlaylist? {
        val kind = obj.str("kind") ?: return null
        val owner = obj.obj("owner")
        val uid = obj.str("uid") ?: owner?.str("uid") ?: return null
        val cover = obj.obj("cover")
        val coverUri = cover?.str("uri")
            ?: cover?.arr("itemsUri")?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: obj.str("ogImage")
        return SpotifyPlaylist(
            id = YandexIds.playlist(uid, kind),
            name = obj.str("title").orEmpty(),
            description = obj.str("description"),
            images = coverImages(coverUri),
            owner = SpotifyPlaylistOwner(
                id = uid,
                displayName = owner?.str("name") ?: owner?.str("login"),
            ),
            tracks = SpotifyPlaylistTracksRef(total = obj.long("trackCount")?.toInt() ?: 0),
            uri = "yandexmusic:playlist:$uid:$kind",
            public = obj.str("visibility")?.let { it == "public" },
        )
    }

    // ── Library ──────────────────────────────────────────────────────────────────────────────────

    fun playlistEntry(p: SpotifyPlaylist, addedAt: String? = null) = SpotifyLibraryEntry(
        kind = SpotifyLibraryEntry.Kind.PLAYLIST,
        id = p.id,
        uri = p.uri ?: p.id,
        name = p.name,
        creator = p.owner?.displayName,
        imageUrl = p.images.firstOrNull { it.width in 200..400 }?.url ?: p.images.firstOrNull()?.url,
        addedAt = addedAt,
        totalCount = p.tracks?.total ?: 0,
    )

    fun albumEntry(a: SpotifySimpleAlbum, addedAt: String? = null, totalCount: Int = 0) = SpotifyLibraryEntry(
        kind = SpotifyLibraryEntry.Kind.ALBUM,
        id = a.id,
        uri = a.uri ?: a.id,
        name = a.name,
        creator = a.artists.joinToString(", ") { it.name }.ifBlank { null },
        imageUrl = a.images.firstOrNull { it.width in 200..400 }?.url ?: a.images.firstOrNull()?.url,
        albumType = a.albumType,
        addedAt = addedAt,
        totalCount = totalCount,
    )

    fun artistEntry(a: SpotifyArtist, addedAt: String? = null) = SpotifyLibraryEntry(
        kind = SpotifyLibraryEntry.Kind.ARTIST,
        id = a.id,
        uri = a.uri ?: a.id,
        name = a.name,
        imageUrl = a.images.firstOrNull { it.width in 200..400 }?.url ?: a.images.firstOrNull()?.url,
        addedAt = addedAt,
    )

    /** One liked-track reference from `/users/{uid}/likes/tracks`: raw id + optional album id. */
    data class TrackRef(val id: String, val albumId: String?, val timestamp: String?) {
        /** The `id:albumId` form `/tracks` accepts; the album pins the exact release. */
        val requestId: String get() = if (albumId != null) "$id:$albumId" else id
    }

    fun likedTrackRefs(result: JsonObject): List<TrackRef> =
        result.obj("library")?.arr("tracks")?.mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            TrackRef(id, o.str("albumId"), o.str("timestamp"))
        }.orEmpty()
}
