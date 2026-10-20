/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.Uri
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.R
import com.metrolist.music.catalog.Catalog
import com.metrolist.spotify.models.SpotifyImage
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * A link pasted into the search box opens what it points to instead of being searched as text:
 * Spotify (open.spotify.com, the short spotify.link), Drxwnify's own links and YouTube videos. The
 * result is shown as the one thing found, ready to play or open.
 */
object LinkSearch {
    private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val SHORT_HOSTS = setOf("spotify.link", "spotify.app.link")
    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** The link in a pasted text ("Artist — Title https://open.spotify.com/track/…?si=…"), if there is one. */
    fun linkIn(text: String): String? = URL.find(text.trim())?.value?.trimEnd('.', ',', ')', '»', '"')

    /** What a pasted link points to, as a one-entry search result; null when it isn't a link the app knows. */
    suspend fun summaryFor(context: Context, text: String): SearchSummaryPage? = withContext(Dispatchers.IO) {
        val url = linkIn(text) ?: return@withContext null
        runCatching { resolve(context, url) }
            .onFailure { Timber.w(it, "LinkSearch: %s", url) }
            .getOrNull()
    }

    private suspend fun resolve(context: Context, link: String): SearchSummaryPage? {
        youTubeVideoId(link)?.let { return youTubeSong(context, it) }
        val target = AppLinks.parse(Uri.parse(expand(link))) ?: return null
        if (target.id.startsWith(AppLinks.YOUTUBE_PREFIX)) {
            return youTubeSong(context, target.id.removePrefix(AppLinks.YOUTUBE_PREFIX))
        }
        // Spotify answers without an account too (its public catalog), but needs a token for it.
        Catalog.ensureSearchable(com.metrolist.music.constants.MetadataSource.SPOTIFY)
        val id = AppLinks.reachableId(target)
        return when (target.kind) {
            AppLinks.Kind.TRACK -> track(id)?.let { page(context.getString(R.string.songs), it.toSongItem()) }
            AppLinks.Kind.ALBUM -> Catalog.album(id).getOrNull()?.let { page(context.getString(R.string.albums), it.toAlbumItem()) }
            AppLinks.Kind.ARTIST -> Catalog.artist(id).getOrNull()?.let { page(context.getString(R.string.artists), it.toArtistItem()) }
            AppLinks.Kind.PLAYLIST -> Catalog.playlist(id).getOrNull()?.let { page(context.getString(R.string.playlists), it.toPlaylistItem()) }
        }
    }

    private fun page(title: String, item: YTItem) = SearchSummaryPage(summaries = listOf(SearchSummary(title = title, items = listOf(item))))

    private suspend fun youTubeSong(context: Context, videoId: String): SearchSummaryPage? =
        YouTube.queue(listOf(videoId)).getOrNull()?.firstOrNull()?.let { page(context.getString(R.string.songs), it) }

    private fun youTubeVideoId(link: String): String? {
        val uri = Uri.parse(link)
        val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val id = when (host) {
            "youtu.be" -> uri.pathSegments.firstOrNull()
            "youtube.com", "music.youtube.com" -> uri.getQueryParameter("v")
                ?: uri.pathSegments.takeIf { it.size >= 2 && it[0] in setOf("shorts", "live", "embed") }?.get(1)
            else -> null
        }
        return id?.takeIf { YOUTUBE_ID.matches(it) }
    }

    /** A short link (spotify.link/…) followed to the full one. */
    private fun expand(link: String): String {
        val host = Uri.parse(link).host?.lowercase() ?: return link
        if (host !in SHORT_HOSTS) return link
        return client.newCall(Request.Builder().url(link).build()).execute().use { r ->
            val final = r.request.url.toString()
            if (Uri.parse(final).host == "open.spotify.com" && Uri.parse(final).pathSegments.size >= 2) {
                final
            } else {
                // Some short links answer with a page that carries the address instead of redirecting.
                Regex("https://open\\.spotify\\.com/[a-z-]+/[A-Za-z0-9]{22}").find(r.body?.string().orEmpty())?.value ?: final
            }
        }
    }

    /**
     * The track a link names. Spotify's track lookup is often refused (rate limit), so then the
     * embed page tells its name, artists, length and cover, and the search finds the full track
     * (with its album) under the same id; failing that, the embed's facts are enough to play it.
     */
    private suspend fun track(id: String): SpotifyTrack? {
        Catalog.getTrack(id).getOrNull()?.let { return it }
        if (Catalog.isYandexId(id)) return null
        val embed = embedTrack(id) ?: return null
        val query = listOfNotNull(embed.artists.firstOrNull()?.name, embed.name).joinToString(" ")
        val found = Catalog.searchIn(com.metrolist.music.constants.MetadataSource.SPOTIFY, query, listOf("track"), 20)
            .getOrNull()?.tracks?.items?.firstOrNull { it.id == id }
        return found ?: embed
    }

    private fun embedTrack(id: String): SpotifyTrack? {
        val html = client.newCall(
            Request.Builder().url("https://open.spotify.com/embed/track/$id").header("User-Agent", "Mozilla/5.0").build(),
        ).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val json = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: return null
        val name = entity.optString("name").ifBlank { return null }
        val artists = entity.optJSONArray("artists")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    SpotifySimpleArtist(id = o.optString("uri").substringAfterLast(':').ifBlank { null }, name = o.optString("name"))
                }
            }
        }.orEmpty()
        val images = entity.optJSONObject("visualIdentity")?.optJSONArray("image")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o -> SpotifyImage(o.optString("url"), o.optInt("maxHeight"), o.optInt("maxWidth")) }
            }
        }.orEmpty()
        return SpotifyTrack(
            id = id,
            name = name,
            artists = artists,
            album = SpotifySimpleAlbum(images = images),
            durationMs = entity.optInt("duration"),
            explicit = entity.optBoolean("isExplicit"),
            uri = "spotify:track:$id",
        )
    }
}
