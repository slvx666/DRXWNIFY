/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.content.Context
import org.json.JSONObject

/**
 * The chat's stickers: pictures inside the app (assets/stickers), so a message carries only a
 * sticker's id, never the picture. Picked from the panel, or from the suggestions that come up
 * while its code is typed (codes are ordinary words, "нет" or "я", so typing one alone never
 * makes a sticker); a message of stickers alone shows them large.
 *
 * Codes and ids are in assets/stickers/stickers.json. A code can be renamed freely (messages keep
 * the id); an id must never change or be reused, or old messages would show another picture.
 */
object Stickers {
    data class Sticker(val id: String, val code: String, val width: Int, val height: Int) {
        val uri: String get() = "file:///android_asset/stickers/$id.webp"
        val aspect: Float get() = if (height > 0) width.toFloat() / height else 1f
    }

    sealed interface Part {
        data class Text(val text: String) : Part
        /** [sticker] is null for an id this version doesn't have (a newer app's sticker). */
        data class Image(val id: String, val sticker: Sticker?) : Part
    }

    private const val START = ''
    private const val END = ''
    private val TOKEN = Regex("$START([a-z0-9_]{1,40})$END")

    @Volatile
    var all: List<Sticker> = emptyList()
        private set
    private var byId: Map<String, Sticker> = emptyMap()

    fun init(context: Context) {
        if (all.isNotEmpty()) return
        runCatching {
            val text = context.assets.open("stickers/stickers.json").bufferedReader().use { it.readText() }
            val list = JSONObject(text).getJSONArray("stickers")
            all = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                Sticker(o.getString("id"), o.getString("code"), o.optInt("w"), o.optInt("h"))
            }
            byId = all.associateBy { it.id }
        }
    }

    fun byId(id: String): Sticker? = byId[id]

    fun token(sticker: Sticker): String = "$START${sticker.id}$END"

    /** Message text as sent: whole stickers stay, the marks of a broken one go. */
    fun clean(text: String): String {
        if (text.none { it == START || it == END }) return text
        val out = StringBuilder()
        var at = 0
        for (m in TOKEN.findAll(text)) {
            text.substring(at, m.range.first).filterTo(out) { it != START && it != END }
            out.append(m.value)
            at = m.range.last + 1
        }
        text.substring(at).filterTo(out) { it != START && it != END }
        return out.toString()
    }

    /** Where the stickers stand in a text (each as start until end, exclusive), and their ids. */
    fun tokens(text: String): List<Pair<IntRange, String>> =
        TOKEN.findAll(text).map { it.range to it.groupValues[1] }.toList()

    /** A message's text in pieces: text, and stickers where they stand. */
    fun parts(text: String): List<Part> {
        val out = mutableListOf<Part>()
        var at = 0
        TOKEN.findAll(text).forEach { m ->
            if (m.range.first > at) out += Part.Text(text.substring(at, m.range.first))
            val id = m.groupValues[1]
            out += Part.Image(id, byId[id])
            at = m.range.last + 1
        }
        if (at < text.length) out += Part.Text(text.substring(at))
        return out
    }

    /** Stickers alone (up to three) and nothing else: shown large, without a bubble. */
    fun stickerOnly(text: String): List<Part.Image>? {
        val parts = parts(text)
        if (parts.any { it is Part.Text && it.text.isNotBlank() }) return null
        val images = parts.filterIsInstance<Part.Image>()
        return images.takeIf { it.size in 1..3 }
    }

    /** For a notification or the chat list: stickers as their codes. */
    fun plain(text: String): String = TOKEN.replace(text) { m -> byId[m.groupValues[1]]?.code ?: "[стикер]" }

    /**
     * Stickers whose code the text before the cursor ends with the start of (a code can be two
     * words: "злой м" → "злой мужик"), each with how many characters of that text it takes the
     * place of. From two letters on, or a whole one-letter code ("я").
     */
    fun suggest(before: String, limit: Int = 8): List<Pair<Sticker, Int>> {
        val tail = before.takeLastWhile { it != START && it != END && it != '\n' }.takeLast(40)
        val out = LinkedHashMap<String, Pair<Sticker, Int>>()
        // From the longest piece (a few words back) to the last word alone.
        for (i in tail.indices) {
            if (tail[i].isWhitespace() || (i > 0 && !tail[i - 1].isWhitespace())) continue
            val piece = tail.substring(i)
            for (s in all) {
                if (s.id in out) continue
                val fits = if (piece.length < 2) s.code.equals(piece, ignoreCase = true) else s.code.startsWith(piece, ignoreCase = true)
                if (fits) out[s.id] = s to piece.length
            }
        }
        return out.values.take(limit)
    }
}
