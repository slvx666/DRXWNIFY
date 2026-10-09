/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver.providers

import com.metrolist.music.resolver.AudioDiagnostics
import com.metrolist.music.resolver.AudioProvider
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.AudioQuery
import com.metrolist.music.resolver.AudioStream
import com.metrolist.music.resolver.ProviderMatch
import com.metrolist.music.resolver.soulseek.SlskProtocol
import com.metrolist.music.resolver.soulseek.SoulseekClient
import com.metrolist.spotify.SpotifyMapper
import java.io.File
import java.security.MessageDigest

/**
 * Soulseek: a peer-to-peer network where people share their own music folders — enormous coverage
 * (label releases, lossless), but every file comes from someone's computer. Last-resort source:
 *  - needs a Soulseek account (free; the first login with a new name creates it);
 *  - slow: the whole file is downloaded from the peer before it plays (seconds to minutes), and peers can
 *    be offline, refuse, or queue us — Drxwnify shares nothing and can't accept incoming connections;
 *  - costs traffic/battery, hence the optional Wi-Fi-only mode.
 */
class SoulseekAudioProvider(
    private val credentials: () -> Pair<String, String>?,
    private val allowedNow: () -> Boolean,
    private val cacheDir: () -> File,
) : AudioProvider {
    override val id = AudioProviderId.SOULSEEK
    override val searchTimeoutMs = SEARCH_WINDOW_MS + 6_000L

    private val client = SoulseekClient(credentials)

    override fun isReady(): Boolean = credentials() != null && allowedNow()

    private data class Pick(val username: String, val file: SlskProtocol.SearchFile)

    private val picks = java.util.concurrent.ConcurrentHashMap<String, Pick>()

    override suspend fun search(query: AudioQuery): ProviderMatch? {
        val text = listOf(query.primaryArtist, SpotifyMapper.spotifyTitleCore(query.title))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            // Soulseek matches words literally; punctuation hurts more than it helps.
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return null
        val responses = client.search(text, SEARCH_WINDOW_MS)
        if (responses.isEmpty()) return null

        val options = responses.flatMap { response ->
            response.files
                .filter { it.extension in PLAYABLE }
                .map { file -> Pick(response.username, file) to response }
        }
        if (options.isEmpty()) return null

        val candidates = options.map { (pick, _) ->
            val segments = pick.file.segments
            val name = segments.lastOrNull().orEmpty().substringBeforeLast('.')
            SpotifyMapper.Candidate(
                id = key(pick),
                // Folder names usually carry the artist and album: let the gate find the artist there.
                title = name,
                artist = segments.dropLast(1).takeLast(2).joinToString(" "),
                durationSec = pick.file.durationSec?.takeIf { it > 0 },
            )
        }
        val ranked = ProviderGate.ranked(query, candidates, limit = 12)
        if (ranked.isEmpty()) return null

        // Among the matching files prefer peers with a free slot and a short queue, then lossless/high bitrate.
        val byKey = options.associateBy { key(it.first) }
        val best = ranked.maxByOrNull { match ->
            val (pick, response) = byKey.getValue(match.id)
            var score = match.score * 100
            if (response.freeSlot) score += 60
            score -= response.queueLength.coerceAtMost(50).toDouble() * 2
            // Soulseek is here for quality (slsk-batchdl style): lossless first, then 320,
            // and low-bitrate files only when nothing better answers.
            score += when {
                pick.file.extension == "flac" -> 45
                (pick.file.bitrate ?: 0) >= 300 -> 30
                (pick.file.bitrate ?: 0) >= 256 -> 20
                (pick.file.bitrate ?: 0) in 1 until 192 -> -40
                else -> 0
            }
            score
        } ?: return null
        val (pick, _) = byKey.getValue(best.id)
        picks[best.id] = pick
        return ProviderMatch(
            provider = id,
            trackId = best.id,
            title = pick.file.segments.lastOrNull().orEmpty(),
            artist = pick.username,
            durationMs = pick.file.durationSec?.times(1000L),
            confidence = best.score,
            qualityKbps = if (pick.file.extension == "flac") 1411 else pick.file.bitrate,
        )
    }

    /**
     * Files matching [text] for the experimental search. Peers with a free slot and a short queue
     * come first (they start sooner), then lossless and high bitrate.
     */
    override suspend fun searchFree(text: String, limit: Int): List<ProviderMatch> {
        val query = text.replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (!isReady() || query.isBlank()) return emptyList()
        val responses = client.search(query, SEARCH_WINDOW_MS)
        return responses
            .flatMap { response ->
                response.files.filter { it.extension in PLAYABLE }.map { Pick(response.username, it) to response }
            }
            .sortedByDescending { (pick, response) ->
                (if (response.freeSlot) 1000 else 0) - response.queueLength.coerceAtMost(50) * 10 +
                    when {
                        pick.file.extension == "flac" -> 300
                        else -> (pick.file.bitrate ?: 0).coerceAtMost(320)
                    }
            }
            .distinctBy { (pick, _) -> pick.file.segments.lastOrNull()?.lowercase() }
            .take(limit)
            .map { (pick, _) ->
                val segments = pick.file.segments
                picks[key(pick)] = pick
                ProviderMatch(
                    provider = id,
                    trackId = key(pick),
                    title = segments.lastOrNull().orEmpty().substringBeforeLast('.'),
                    // The folder usually names the artist/album; the peer is shown when it doesn't.
                    artist = segments.dropLast(1).lastOrNull() ?: pick.username,
                    durationMs = pick.file.durationSec?.times(1000L),
                    confidence = 0.0,
                )
            }
    }

    override suspend fun stream(query: AudioQuery, match: ProviderMatch): AudioStream? {
        val pick = picks[match.trackId] ?: decodeKey(match.trackId) ?: return null
        if (!allowedNow()) return null
        val dir = File(cacheDir(), "soulseek").apply { mkdirs() }
        val target = File(dir, sha1(match.trackId) + "." + pick.file.extension)
        AudioDiagnostics.info("soulseek: downloading '${pick.file.segments.lastOrNull()}' from ${pick.username}")
        val file = client.download(pick.username, pick.file.filename, target, DOWNLOAD_TIMEOUT_MS) ?: return null
        trimCache(dir)
        val (mime, codecs) = when (pick.file.extension) {
            "flac" -> "audio/flac" to "flac"
            "m4a", "mp4" -> "audio/mp4" to "mp4a.40.2"
            "ogg", "opus" -> "audio/ogg" to "opus"
            else -> "audio/mpeg" to "mp3"
        }
        return AudioStream(
            uri = file.toURI().toString(),
            mimeType = mime,
            codecs = codecs,
            // What the peer reported, else worked out from the downloaded file itself.
            bitrate = pick.file.bitrate?.times(1000)
                ?: pick.file.durationSec?.takeIf { it > 0 }?.let { (file.length() * 8 / it).toInt() }
                ?: 0,
            sampleRate = pick.file.sampleRate,
            expiresAtMs = Long.MAX_VALUE / 2,
            contentLength = file.length(),
        )
    }

    /** Keeps the local Soulseek file cache under [CACHE_LIMIT_BYTES]; the player/download caches hold copies. */
    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    // The match id must survive restarts (it is stored in the resolution cache): user + size + path.
    private fun key(pick: Pick) = "${pick.username}\u001F${pick.file.size}\u001F${pick.file.filename}"

    private fun decodeKey(key: String): Pick? {
        val parts = key.split('\u001F')
        if (parts.size != 3) return null
        return Pick(parts[0], SlskProtocol.SearchFile(parts[2], parts[1].toLongOrNull() ?: 0, null, null, null, null))
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SEARCH_WINDOW_MS = 6_000L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
        private const val CACHE_LIMIT_BYTES = 300L * 1024 * 1024
        private val PLAYABLE = setOf("mp3", "flac", "m4a", "ogg", "opus")
    }
}
