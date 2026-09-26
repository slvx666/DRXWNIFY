/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R

/** Where "low quality" starts, per codec family. */
object AudioQualityLevel {
    /** MP3 needs more bits than the modern codecs for the same result; 128 kbps MP3 is audibly poor. */
    private const val MP3_MIN_GOOD = 192_000

    /** AAC / Opus / Vorbis sound fine from ~128 kbps; below ~120 kbps they don't. */
    private const val MODERN_MIN_GOOD = 120_000

    fun isLow(bitrate: Int, mimeType: String?): Boolean {
        if (bitrate <= 0) return false
        val mp3 = mimeType?.startsWith("audio/mpeg") == true || mimeType?.startsWith("audio/mp3") == true
        return bitrate < if (mp3) MP3_MIN_GOOD else MODERN_MIN_GOOD
    }

    fun kbps(bitrate: Int): Int = (bitrate + 500) / 1000

    /** "Opus", "AAC", "MP3", "FLAC"… as people know them, from a mime type and codec string. */
    fun codecName(mimeType: String?, codecs: String?): String? {
        val all = "${mimeType.orEmpty()} ${codecs.orEmpty()}".lowercase()
        return when {
            "opus" in all -> "Opus"
            "flac" in all -> "FLAC"
            "vorbis" in all -> "Vorbis"
            "mp4a" in all || "aac" in all || "audio/mp4" in all -> "AAC"
            "mpeg" in all || "mp3" in all -> "MP3"
            "alac" in all -> "ALAC"
            else -> codecs?.takeIf { it.isNotBlank() } ?: mimeType?.substringAfter('/')?.uppercase()
        }
    }

    /** The short quality line: "VK · MP3 · 320 kbps" (source only when it isn't YouTube). */
    fun summary(source: String?, mimeType: String?, codecs: String?, bitrate: Int?): String? {
        val parts = listOfNotNull(
            source?.takeIf { it.isNotBlank() && it != "YouTube" },
            codecName(mimeType, codecs),
            bitrate?.takeIf { it > 0 }?.let { "${kbps(it)} kbps" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

/**
 * A small warning pill for the track that is playing: shown only when the stream actually used is of
 * low quality (e.g. "Low quality · 96 kbps"); a good stream shows nothing.
 */
@Composable
fun LowQualityBadge(mediaId: String?, modifier: Modifier = Modifier) {
    if (mediaId.isNullOrBlank()) return
    val database = LocalDatabase.current
    val format by remember(mediaId) { database.format(mediaId) }.collectAsState(initial = null)
    val current = format ?: return
    if (!AudioQualityLevel.isLow(current.bitrate, current.mimeType)) return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.low_quality_badge, AudioQualityLevel.kbps(current.bitrate)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
