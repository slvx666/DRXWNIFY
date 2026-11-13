/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.catalog.Catalog
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers for tracks that came without one — VK uploads by users above all: until such a track was
 * played it showed a blank square in the playlist and the library. The same track is looked up in
 * the search catalog (Spotify's public one) and its album's cover kept on the song. Slowly, in the
 * background, each track once per run.
 */
object MissingCovers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tried = ConcurrentHashMap.newKeySet<String>()

    fun fill(database: MusicDatabase, songs: List<Song>) {
        val bare = songs.filter { it.song.thumbnailUrl.isNullOrBlank() && tried.add(it.id) }.take(MAX_PER_CALL)
        if (bare.isEmpty()) return
        scope.launch {
            if (!Catalog.ensureSearchable(MetadataSource.SPOTIFY)) return@launch
            for (song in bare) {
                val artist = song.artists.firstOrNull()?.name
                val title = song.song.title
                val query = listOfNotNull(artist, title).joinToString(" ")
                val found = Catalog.searchIn(MetadataSource.SPOTIFY, query, listOf("track"), 8).getOrNull()?.tracks?.items.orEmpty()
                val wanted = norm(title)
                val match = found.firstOrNull { t ->
                    val n = norm(t.name)
                    (n == wanted || n.startsWith(wanted) || wanted.startsWith(n)) &&
                        (artist == null || t.artists.any { norm(it.name) == norm(artist) })
                }
                val cover = match?.album?.images?.let { images ->
                    images.firstOrNull { (it.width ?: 0) in 250..700 } ?: images.firstOrNull()
                }?.url
                if (cover != null) {
                    database.getSongByIdBlocking(song.id)?.song?.takeIf { it.thumbnailUrl.isNullOrBlank() }?.let {
                        database.update(it.copy(thumbnailUrl = cover))
                    }
                }
                delay(PAUSE_MS)
            }
        }
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private const val MAX_PER_CALL = 200
    private const val PAUSE_MS = 350L
}
