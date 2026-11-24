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
    /** [animated]: a moving picture (an animated WebP, like a Telegram GIF), shown a little larger. */
    data class Sticker(val id: String, val code: String, val width: Int, val height: Int, val animated: Boolean = false) {
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

    /** The stickers one can pick (the panel, suggestions, reactions); retired ones are left out. */
    @Volatile
    var all: List<Sticker> = emptyList()
        private set
    /** Every sticker, retired ones too ("hidden" in the json), so old messages still show them. */
    private var byId: Map<String, Sticker> = emptyMap()

    /** How often each sticker was sent here, and when last: the panel shows the most used first. */
    @Volatile
    private var use: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (use == null) use = context.applicationContext.getSharedPreferences("sticker_use", Context.MODE_PRIVATE)
        if (all.isNotEmpty()) return
        runCatching {
            val text = context.assets.open("stickers/stickers.json").bufferedReader().use { it.readText() }
            val list = JSONObject(text).getJSONArray("stickers")
            val every = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                Sticker(o.getString("id"), o.getString("code"), o.optInt("w"), o.optInt("h"), o.optBoolean("anim")) to o.optBoolean("hidden")
            }
            byId = every.associate { it.first.id to it.first }
            all = every.filterNot { it.second }.map { it.first }
        }
    }

    fun byId(id: String): Sticker? = byId[id]

    /** Goes up each time a sticker is sent, so the panel re-sorts. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** A message going out: its stickers count as used. */
    fun noteSent(text: String) {
        val prefs = use ?: return
        val ids = tokens(text).map { it.second }.filter { it in byId }
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            ids.groupingBy { it }.eachCount().forEach { (id, n) ->
                putInt("n:$id", prefs.getInt("n:$id", 0) + n)
                putLong("t:$id", now)
            }
        }.apply()
        version.value++
    }

    /** The pickable stickers, the most used first (then the most recently used), the rest as listed. */
    fun byUse(): List<Sticker> {
        val prefs = use ?: return all
        return all.withIndex().sortedWith(
            compareByDescending<IndexedValue<Sticker>> { prefs.getInt("n:${it.value.id}", 0) }
                .thenByDescending { prefs.getLong("t:${it.value.id}", 0L) }
                .thenBy { it.index },
        ).map { it.value }
    }

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
