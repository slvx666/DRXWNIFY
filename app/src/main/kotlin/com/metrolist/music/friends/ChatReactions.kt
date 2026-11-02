/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Reactions to chat messages: what can be put (built-in emoji and the app's stickers, both shipped
 * in the APK), the one a double tap puts, and which ones this person uses most.
 *
 * A reaction is a key: "e:<emoji>" or "s:<sticker id>". A key this version doesn't know (a newer
 * app's) is kept as it came and shown as a neutral chip, so sending a set back never erases it.
 */
object ChatReactions {
    /** Reactions one person can put on one message. */
    const val MAX = 3

    sealed interface Reaction {
        val key: String

        data class Emoji(val emoji: String, val words: String) : Reaction {
            override val key get() = "e:$emoji"
        }

        data class Sticker(val sticker: Stickers.Sticker) : Reaction {
            override val key get() = "s:${sticker.id}"
        }
    }

    /**
     * The built-in emoji, all from Unicode 10 or older so the system font on Android 8 draws them
     * (the foss build has no downloadable emoji font). Words after each are what the search finds.
     */
    private val EMOJI = listOf(
        "❤️" to "сердце любовь heart love",
        "👍" to "класс лайк да like yes",
        "🔥" to "огонь круто fire",
        "😂" to "смех ржу laugh lol",
        "😭" to "плачу слёзы cry",
        "😍" to "влюблён любовь love",
        "🤯" to "взрыв мозга шок mind",
        "👎" to "плохо нет dislike",
        "😢" to "грусть sad",
        "😮" to "удивление вау wow",
        "😡" to "злость angry",
        "🤔" to "думаю хм think",
        "👏" to "аплодисменты браво clap",
        "🎉" to "праздник ура party",
        "💯" to "сто точно hundred",
        "🙏" to "спасибо пожалуйста please thanks",
        "👀" to "глаза смотрю eyes",
        "😎" to "круто очки cool",
        "🤣" to "угар смех rofl",
        "😁" to "улыбка grin",
        "🤗" to "обнимаю hug",
        "🤝" to "договорились рука deal",
        "💔" to "разбитое сердце broken",
        "😱" to "ужас крик scream",
        "🤮" to "фу тошнит vomit",
        "🤡" to "клоун clown",
        "💩" to "какашка poo",
        "💀" to "череп умер dead skull",
        "🙈" to "обезьянка стыд monkey",
        "😴" to "сон сплю sleep",
        "😈" to "дьявол devil",
        "🤬" to "мат ругань swear",
        "🌚" to "луна moon",
        "⚡" to "молния zap",
        "🏆" to "кубок победа trophy",
        "🎵" to "музыка нота music",
        "🎧" to "наушники слушаю headphones",
        "🍓" to "клубника strawberry",
        "✅" to "готово check",
        "🙄" to "закатил глаза eyeroll",
    ).map { (e, w) -> Reaction.Emoji(e, w) }

    private val emojiByKey = EMOJI.associateBy { it.key }

    /** Every reaction: the emoji, then the stickers. */
    val all: List<Reaction> get() = EMOJI + Stickers.all.map { Reaction.Sticker(it) }

    /** What [key] shows; null for a key this version doesn't know. */
    fun of(key: String): Reaction? = when {
        key.startsWith("e:") -> emojiByKey[key]
        key.startsWith("s:") -> Stickers.byId(key.removePrefix("s:"))?.let { Reaction.Sticker(it) }
        else -> null
    }

    /** A key as it may travel: short, one line, our two kinds. */
    fun isWellFormed(key: String): Boolean =
        key.length in 3..48 && '\n' !in key && (key.startsWith("e:") || key.startsWith("s:"))

    fun search(query: String): List<Reaction> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter {
            when (it) {
                is Reaction.Emoji -> it.words.contains(q) || it.emoji == q
                is Reaction.Sticker -> it.sticker.code.lowercase().contains(q)
            }
        }
    }

    /** [current] with [key] put (the oldest goes when there would be more than [MAX]) or taken off. */
    fun toggle(current: List<String>, key: String): List<String> =
        if (key in current) current - key else (current + key).takeLast(MAX)

    // ── This person's choices ──

    private var prefs: SharedPreferences? = null

    private val DEFAULT_QUICK = EMOJI.first().key

    /** The first seven before there is a history to go by. */
    private val DEFAULT_TOP = EMOJI.take(7).map { it.key }

    private val _quick = MutableStateFlow(DEFAULT_QUICK)
    /** What a double tap puts (chosen in Settings → Messages). */
    val quick: StateFlow<String> = _quick

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("chat", Context.MODE_PRIVATE)
        prefs = p
        _quick.value = p.getString("quickReaction", null)?.takeIf { of(it) != null } ?: DEFAULT_QUICK
    }

    fun setQuick(key: String) {
        _quick.value = key
        prefs?.edit()?.putString("quickReaction", key)?.apply()
    }

    /** Counts each reaction put: the menu offers the most used first. */
    fun used(key: String) {
        val p = prefs ?: return
        val o = runCatching { JSONObject(p.getString("reactionUse", "{}")!!) }.getOrDefault(JSONObject())
        o.put(key, o.optInt(key) + 1)
        p.edit().putString("reactionUse", o.toString()).apply()
    }

    /**
     * The seven offered above a message's menu: the quick reaction, then the most used (ties keep the
     * default order), filled up from the defaults. Read once when the menu opens, so nothing moves under the finger.
     */
    fun top(): List<String> {
        val o = runCatching { JSONObject(prefs?.getString("reactionUse", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val counts = o.keys().asSequence().associateWith { o.optInt(it) }.filterKeys { of(it) != null }
        val order = DEFAULT_TOP.withIndex().associate { it.value to it.index }
        val byUse = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { order[it.key] ?: Int.MAX_VALUE })
            .map { it.key }
        return (listOf(_quick.value) + byUse + DEFAULT_TOP).distinct().take(7)
    }
}
