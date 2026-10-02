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
import androidx.compose.ui.text.withStyle
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

private const val LOSSLESS_CHECK_DELAY_MS = 20_000L

/** Gold for genuine lossless. */
val LosslessGold = androidx.compose.ui.graphics.Color(0xFFE2B84C)

/**
 * The small line above the cover in the full-screen player: bitrate (in its quality colour), codec,
 * sample rate and the service the audio comes from — "320 kbps · MP3 · 44.1 kHz · VK". Lossless is
 * written in gold, unless the spectrum check found it to be an upscaled lossy file.
 */
@Composable
fun PlayerQualityLine(mediaId: String?, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    if (mediaId.isNullOrBlank()) return
    val database = LocalDatabase.current
    val format by remember(mediaId) { database.format(mediaId) }.collectAsState(initial = null)
    val served by com.metrolist.music.resolver.NowServing.version.collectAsState()
    val source by androidx.compose.runtime.produceState<String?>(null, mediaId, served, format) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (com.metrolist.music.resolver.NowServing.of(mediaId)
                ?: com.metrolist.music.resolver.AudioFallbackEngine.sourceOf(mediaId, database.getSongByIdBlocking(mediaId)))
                ?.let(::providerLabel)
        }
    }
    val current = format ?: return
    val codec = AudioQualityLevel.codecName(current.mimeType, current.codecs)
    val lossless = codec.equals("FLAC", ignoreCase = true) || codec.equals("ALAC", ignoreCase = true)
    val verdict by com.metrolist.music.playback.SpectrumCheck.verdictFlow(mediaId).collectAsState(initial = null)
    val genuineLossless = lossless && verdict?.upscaled != true
    // Lossless is checked by itself once enough of it has played: gold only for the real thing.
    val downloadUtil = com.metrolist.music.LocalDownloadUtil.current
    if (lossless && verdict == null) {
        androidx.compose.runtime.LaunchedEffect(mediaId) {
            kotlinx.coroutines.delay(LOSSLESS_CHECK_DELAY_MS)
            com.metrolist.music.playback.SpectrumCheck.analyze(
                mediaId, codec, current.bitrate.takeIf { it > 0 }?.let { AudioQualityLevel.kbps(it) },
                listOf(downloadUtil.downloadCache, downloadUtil.playerCache),
            )
        }
    }
    val kbps = current.bitrate.takeIf { it > 0 }?.let { AudioQualityLevel.kbps(it) }
    val khz = current.sampleRate?.takeIf { it > 0 }?.let { hz ->
        val v = hz / 1000.0
        if (v % 1.0 == 0.0) "${v.toInt()} kHz" else "$v kHz"
    }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        var first = true
        fun sep() { if (!first) append(" · "); first = false }
        if (genuineLossless) {
            sep()
            withStyle(androidx.compose.ui.text.SpanStyle(color = LosslessGold, fontWeight = FontWeight.SemiBold)) { append(codec ?: "FLAC") }
            kbps?.let { sep(); append("$it kbps") }
        } else {
            kbps?.let {
                sep()
                withStyle(androidx.compose.ui.text.SpanStyle(color = com.metrolist.music.ui.dialog.bitrateColor(it), fontWeight = FontWeight.SemiBold)) { append("$it kbps") }
            }
            codec?.let { sep(); append(it) }
        }
        khz?.let { sep(); append(it) }
        source?.let { sep(); append(it) }
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color.copy(alpha = 0.75f),
        maxLines = 1,
        modifier = modifier,
    )
}
