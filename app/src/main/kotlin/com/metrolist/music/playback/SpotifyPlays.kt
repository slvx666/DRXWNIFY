package com.metrolist.music.playback

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.metrolist.music.LocalDatabase
import com.metrolist.spotify.models.SpotifyPlayCounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Spotify's stream counts: kept between launches, so the player can show them for any track it knows. */
object SpotifyPlays {
    private const val PREFS = "spotify_plays"
    private const val MAX_SAVED = 4000
    private var saveJob: Job? = null

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        SpotifyPlayCounts.load(prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())
        val scope = CoroutineScope(Dispatchers.IO)
        SpotifyPlayCounts.onNew = {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(3000)
                val all = SpotifyPlayCounts.snapshot()
                prefs.edit().clear().apply { all.entries.take(MAX_SAVED).forEach { putLong(it.key, it.value) } }.apply()
            }
        }
    }

    /** "1,234,567" — the way Spotify writes it next to a track. */
    fun format(count: Long): String = NumberFormat.getIntegerInstance().format(count)

    /** "12 Oct 2026" for a known day, the bare year when that is all Spotify has. */
    fun releaseDate(iso: String?): String? {
        val s = iso?.takeIf { it.length >= 4 } ?: return null
        if (s.length < 10) return s.take(4)
        return runCatching {
            LocalDate.parse(s.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
        }.getOrDefault(s.take(4))
    }
}

/** The stream count of the song with [mediaId] (the YouTube id it plays through), when its Spotify twin is known. */
@Composable
fun rememberPlayCount(mediaId: String?): Long? {
    val database = LocalDatabase.current
    val count by produceState<Long?>(null, mediaId) {
        value = if (mediaId.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            val spotifyId = runCatching { SpotifyMetadataRegistry.catalogIdOf(database, mediaId) }.getOrNull()
                ?: runCatching { database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId }.getOrNull()
            SpotifyPlayCounts[spotifyId]
        }
    }
    return count
}
