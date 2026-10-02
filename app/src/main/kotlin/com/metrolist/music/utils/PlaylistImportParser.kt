/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns what people bring from other apps into a list of "artist – title" wishes:
 *  - a Telegram Desktop chat export (`result.json`): only audio files, text/photos/voice skipped;
 *  - M3U / M3U8 (AIMP, VLC, Winamp…) and AIMP's own playlist file;
 *  - CSV (Exportify, TuneMyMusic, Soundiiz…) with track and artist columns;
 *  - plain lines "Artist - Title";
 *  - links (Spotify / YouTube playlists and albums) — returned separately, fetched by the caller.
 */
object PlaylistImportParser {
    data class Entry(val artist: String?, val title: String, val durationSec: Int? = null) {
        val label: String get() = listOfNotNull(artist, title).joinToString(" – ")
    }

    data class Parsed(val entries: List<Entry>, val links: List<String>)

    private val LINK = Regex("https?://\\S+")

    fun parse(text: String): Parsed {
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseTelegram(trimmed)?.let { return Parsed(it, emptyList()) }
        if ("media_audio_file" in trimmed) return Parsed(parseTelegramHtml(trimmed), emptyList())
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val links = lines.flatMap { line -> LINK.findAll(line).map { it.value.trimEnd(',', ';', ')') }.toList() }
            .filter { isPlaylistLink(it) }
        val rest = lines.filterNot { line -> LINK.containsMatchIn(line) && links.any { it in line } }
        val entries = when {
            rest.any { it.startsWith("#EXTM3U") || it.startsWith("#EXTINF") } -> parseM3u(rest)
            rest.any { it.startsWith("#-----") } || rest.count { it.count { c -> c == '|' } >= 3 } > rest.size / 2 -> parseAimp(rest)
            looksLikeCsv(rest) -> parseCsv(rest)
            else -> rest.mapNotNull(::parseLine)
        }
        return Parsed(entries.distinctBy { it.label.lowercase() }, links.distinct())
    }

    fun isPlaylistLink(url: String): Boolean =
        ("open.spotify.com/" in url && ("/playlist/" in url || "/album/" in url)) ||
            (("youtube.com/" in url || "youtu.be/" in url) && "list=" in url)

    /** Telegram Desktop "Export chat history" as JSON: messages with media_type = audio_file. */
    private fun parseTelegram(text: String): List<Entry>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val chats = buildList {
            root.optJSONArray("messages")?.let { add(it) }
            // A whole-account export holds several chats.
            root.optJSONObject("chats")?.optJSONArray("list")?.let { list ->
                for (i in 0 until list.length()) list.optJSONObject(i)?.optJSONArray("messages")?.let { add(it) }
            }
        }
        if (chats.isEmpty()) return null
        return chats.flatMap { messages: JSONArray ->
            (0 until messages.length()).mapNotNull { i ->
                val m = messages.optJSONObject(i) ?: return@mapNotNull null
                if (m.optString("media_type") != "audio_file") return@mapNotNull null
                val performer = m.optString("performer").takeIf { it.isNotBlank() }
                val title = m.optString("title").takeIf { it.isNotBlank() }
                val duration = m.optInt("duration_seconds").takeIf { it > 0 }
                if (title != null) {
                    Entry(performer, title, duration)
                } else {
                    // No tags: the file name ("Artist - Title.mp3").
                    val name = m.optString("file_name").takeIf { it.isNotBlank() && !it.startsWith("(") }
                        ?: m.optString("file").substringAfterLast('/').takeIf { it.isNotBlank() && !it.startsWith("(") }
                        ?: return@mapNotNull null
                    parseLine(name.substringBeforeLast('.'))?.copy(durationSec = duration)
                }
            }
        }
    }

    /**
     * Telegram Desktop's HTML export (messages.html, messages2.html, …): every music file is a
     * `media_audio_file` block whose title reads "Artist – Title" and whose details start with
     * its length ("05:48, 13.8 MB"). Everything else in the chat is ignored.
     */
    private fun parseTelegramHtml(html: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var from = 0
        while (true) {
            val at = html.indexOf("media_audio_file", from)
            if (at < 0) break
            val next = html.indexOf("media_audio_file", at + 16).let { if (it < 0) html.length else it }
            val block = html.substring(at, next)
            from = at + 16
            val title = HTML_TITLE.find(block)?.groupValues?.get(1)?.let(::htmlText)?.takeIf { it.isNotBlank() } ?: continue
            val duration = HTML_DURATION.find(block)?.let { m ->
                val parts = m.groupValues[1].split(':').mapNotNull { it.toIntOrNull() }
                parts.fold(0) { acc, v -> acc * 60 + v }.takeIf { it > 0 }
            }
            parseLine(title)?.let { out += it.copy(durationSec = duration) }
        }
        return out
    }

    private val HTML_TITLE = Regex("""<div class="title bold">\s*(.*?)\s*</div>""", RegexOption.DOT_MATCHES_ALL)
    private val HTML_DURATION = Regex("""<div class="status details">\s*((?:\d{1,2}:)?\d{1,2}:\d{2})""")

    private fun htmlText(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseM3u(lines: List<String>): List<Entry> {
        val out = mutableListOf<Entry>()
        var pending: Entry? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val duration = line.substringAfter(':').substringBefore(',').trim().toIntOrNull()?.takeIf { it > 0 }
                    pending = parseLine(line.substringAfter(',', ""))?.copy(durationSec = duration)
                }
                line.startsWith("#") -> Unit
                else -> {
                    out += pending ?: parseLine(fileTitle(line)) ?: continue
                    pending = null
                }
            }
        }
        pending?.let { out += it }
        return out
    }

    /** AIMP playlist (.aimppl4): content lines are "path|title|artist|album|…". */
    private fun parseAimp(lines: List<String>): List<Entry> =
        lines.filter { it.count { c -> c == '|' } >= 3 && !it.startsWith("#") }.mapNotNull { line ->
            val f = line.split('|')
            val title = f.getOrNull(1)?.trim().orEmpty()
            val artist = f.getOrNull(2)?.trim().orEmpty()
            when {
                title.isNotEmpty() -> Entry(artist.ifEmpty { null }, title)
                else -> parseLine(fileTitle(f[0]))
            }
        }

    private fun looksLikeCsv(lines: List<String>): Boolean {
        val header = lines.firstOrNull()?.lowercase() ?: return false
        return ("," in header || ";" in header) && ("track" in header || "title" in header || "название" in header) &&
            ("artist" in header || "исполнитель" in header)
    }

    private fun parseCsv(lines: List<String>): List<Entry> {
        val sep = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val header = splitCsv(lines.first(), sep).map { it.lowercase() }
        val titleCol = header.indexOfFirst { "track name" in it || it == "title" || it == "track" || "название" in it || "track name" == it }
            .takeIf { it >= 0 } ?: header.indexOfFirst { "track" in it || "title" in it }
        val artistCol = header.indexOfFirst { "artist" in it || "исполнитель" in it }
        val durationCol = header.indexOfFirst { "duration" in it }
        if (titleCol < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val f = splitCsv(line, sep)
            val title = f.getOrNull(titleCol)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Exportify lists several artists comma-joined inside one quoted field.
            val artist = f.getOrNull(artistCol)?.trim()?.split(',', ';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val duration = f.getOrNull(durationCol)?.trim()?.toLongOrNull()?.let { if (it > 10_000) (it / 1000).toInt() else it.toInt() }
            Entry(artist, title, duration)
        }
    }

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun fileTitle(path: String): String =
        path.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')

    /** "Artist - Title", "Artist — Title", "01. Artist - Title"; a lone title is kept as is. */
    fun parseLine(raw: String): Entry? {
        val line = raw.replace('_', ' ')
            .replace(Regex("^\\s*\\d{1,3}\\s*[.)\\-]\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (line.isEmpty()) return null
        val m = Regex("\\s[-–—]\\s").find(line)
        return if (m != null) {
            Entry(line.substring(0, m.range.first).trim(), line.substring(m.range.last + 1).trim())
        } else {
            Entry(null, line)
        }
    }
}
