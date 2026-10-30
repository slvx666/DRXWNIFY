/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.concerts

import timber.log.Timber
import java.time.LocalDate
import java.time.LocalTime

/**
 * Public Telegram channels read through their web preview (`t.me/s/<channel>`, no account, no
 * server of ours): gig posters of Saint Petersburg and the venues' own channels, where the small
 * and free gigs are announced. A post is free text, so this only takes what it can be sure of: a
 * date (with the time when given), the act (the first line, or each line of a digest), the venue
 * (named in the post, or the channel's own) and a price or "free". Gigs in other cities are left out.
 */
internal object TelegramPosters {
    /** A channel and, for a venue's own channel, the venue every post is at. */
    data class Channel(val name: String, val label: String, val venue: String? = null, val address: String = "")

    val CHANNELS = listOf(
        Channel("spbgigs", "SPB GIGS"),
        Channel("gigsspb", "Нишевые концерты СПб"),
        Channel("golyiwood", "Golyj Wood", venue = "Golyj Wood", address = "Грузинская 3к1Б"),
        Channel("ionotekaspb", "Ионотека", venue = "Ионотека", address = "Лиговский пр., 50"),
    )

    /** Each channel's preview shows ~20 posts per page; two pages reach a few weeks back. */
    private const val PAGES = 2

    private val months = listOf(
        "январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр",
    )
    private val wordDate = Regex("""(?<!\d)(\d{1,2})\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)""", RegexOption.IGNORE_CASE)
    private val dotDate = Regex("""(?<![\d.])(\d{1,2})\.(\d{2})(?:\.(\d{2,4}))?(?![\d.])""")
    private val clock = Regex("""(?<![\d.])([01]?\d|2[0-3]):([0-5]\d)(?!\d)""")
    /** Words that make a post a gig announcement (an aggregator also posts news). */
    private val gigWords = Regex("""билет|концерт|вход|сцен|лайв|live|презентац|фест|тур\b|сыгра|выступ|gig|сет\b|donat|донат""", RegexOption.IGNORE_CASE)
    private val notAddress = Regex("""билет|clck|http|→|t\.me|vk\.com""", RegexOption.IGNORE_CASE)
    private val rub = Regex("""(\d[\d  ]{1,6})\s?(?:₽|руб|р\.|rub)""", RegexOption.IGNORE_CASE)
    private val free = Regex("""бесплатн|вход свободный|вход free|free entry|свободный вход|вход — free|за донат|донейшн""", RegexOption.IGNORE_CASE)
    private val elsewhere = Regex("""москв|екатеринбург|казан|новосиб|нижн\w* новгород|минск|краснодар""", RegexOption.IGNORE_CASE)
    private val here = Regex("""петербург|спб|питер|ленобласт""", RegexOption.IGNORE_CASE)
    private val quoted = Regex("""[«"]([^»"]{2,40})[»"]""")
    private val digestLine = Regex("""^\s*(\d{1,2})\.(\d{2})\s*[·•|\-–—]\s*(.+?)\s+[—–-]\s+(.+?)\s*(?:🎫.*)?$""")

    private val message = Regex("""<div class="tgme_widget_message [^"]*"[^>]*data-post="([^"]+)"(.*?)(?=<div class="tgme_widget_message_wrap|\z)""", RegexOption.DOT_MATCHES_ALL)
    private val text = Regex("""<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val photo = Regex("""tgme_widget_message_photo_wrap[^"]*"[^>]*background-image:url\('([^']+)'\)""")
    private val before = Regex("""<a class="tme_messages_more[^"]*"[^>]*href="[^"]*\?before=(\d+)"""")

    fun fetch(get: (String) -> String): List<Concert> {
        val out = ArrayList<Concert>()
        var ok = 0
        for (channel in CHANNELS) {
            var url = "https://t.me/s/${channel.name}"
            repeat(PAGES) {
                val page = runCatching { get(url) }.onFailure { Timber.w(it, "Concerts: t.me/%s failed", channel.name) }.getOrNull()
                    ?: return@repeat
                ok++
                message.findAll(page).forEach { m ->
                    runCatching { parsePost(channel, m.groupValues[1], m.groupValues[2]) }.getOrNull()?.let(out::addAll)
                }
                url = before.find(page)?.groupValues?.get(1)?.let { "https://t.me/s/${channel.name}?before=$it" } ?: return@repeat
            }
        }
        if (ok == 0) error("telegram: no channel page loaded")
        // A venue posts about the same night several times (lineup, dress code, "tonight"): one per day.
        val venueNights = HashSet<String>()
        out.removeAll { c ->
            val own = CHANNELS.firstOrNull { it.venue != null && c.sources.firstOrNull() == "Telegram · ${it.label}" } ?: return@removeAll false
            !venueNights.add("${own.name}|${c.date}")
        }
        Timber.d("Concerts: telegram gave ${out.size}")
        return out
    }

    private fun plain(html: String): String =
        htmlDecode(html.replace(Regex("""<br\s*/?>"""), "\n").replace(Regex("""<[^>]+>"""), ""))

    /** The gigs a post announces: one per digest line, else at most one. */
    internal fun parsePost(channel: Channel, post: String, html: String, today: LocalDate = LocalDate.now(Concert.ZONE)): List<Concert> {
        val body = text.find(html)?.groupValues?.get(1) ?: return emptyList()
        val content = plain(body)
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val link = "https://t.me/$post"
        val image = photo.find(html)?.groupValues?.get(1)

        // A digest: "01.11 · Jane Air — Base 🎫", one gig per line.
        val digest = lines.mapNotNull { digestLine.find(it) }
        if (digest.size >= 3) {
            return digest.mapNotNull { m ->
                val date = dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, today) ?: return@mapNotNull null
                val act = clean(m.groupValues[3])
                val venue = clean(m.groupValues[4])
                if (act.length < 2 || venue.length < 2) return@mapNotNull null
                // The digest's words describe the month, not this act: no genre guessed from them.
                concert(channel, "$post#${date}_${act.hashCode()}", act, date, null, venue, "", null, link, null, "")
            }
        }

        // One announcement: the first line naming a date in Petersburg (or with no city at all).
        val dateLineIndex = lines.indexOfFirst { line ->
            (wordDate.containsMatchIn(line) || dotDate.containsMatchIn(line)) && !(elsewhere.containsMatchIn(line) && !here.containsMatchIn(line))
        }
        if (dateLineIndex < 0) return emptyList()
        if (channel.venue == null && !gigWords.containsMatchIn(content)) return emptyList()
        val dateLine = lines[dateLineIndex]
        // "13 декабря в Москве и 20 декабря в Санкт-Петербурге": the date next to Petersburg.
        val dateText = if (elsewhere.containsMatchIn(dateLine) && here.containsMatchIn(dateLine)) {
            dateLine.split(Regex("""\s+и\s+|,|;""")).firstOrNull { here.containsMatchIn(it) && (wordDate.containsMatchIn(it) || dotDate.containsMatchIn(it)) } ?: return emptyList()
        } else {
            dateLine
        }
        val next = lines.getOrNull(dateLineIndex + 1).orEmpty()
        if (elsewhere.containsMatchIn(next) && !here.containsMatchIn(next) && !here.containsMatchIn(dateLine)) return emptyList()
        val date = wordDate.find(dateText)?.let { m ->
            val month = months.indexOfFirst { m.groupValues[2].lowercase().startsWith(it) } + 1
            dateOf(m.groupValues[1].toInt(), month, null, today)
        } ?: dotDate.find(dateText)?.let { m ->
            dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), today)
        } ?: return emptyList()
        val time = (clock.find(dateText) ?: clock.find(next))?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

        val title = clean(lines.firstOrNull { !it.contains("pinned", true) && it.any(Char::isLetter) } ?: return emptyList())
            .let { if (it.length > TITLE_MAX) it.take(TITLE_MAX).substringBeforeLast(' ') + "…" else it }
        if (title.length < 2) return emptyList()
        // The venue: the channel's own, else "| Кадры" / «Ласточка» on the date line, else none.
        val venue = channel.venue
            ?: dateText.substringAfter('|', "").let(::clean).takeIf { it.length in 2..40 }
            // A name in quotes counts only on a short "date, «venue»" line, not in a sentence.
            ?: quoted.find(dateText)?.takeIf { dateText.length <= 60 }?.groupValues?.get(1)?.let(::clean)
            ?: quoted.find(next)?.takeIf { next.length <= 60 }?.groupValues?.get(1)?.let(::clean)
            ?: return emptyList()
        val address = channel.address.ifBlank {
            next.takeIf { l -> l.any(Char::isDigit) && !clock.matches(l) && l.length < 60 && !notAddress.containsMatchIn(l) }.orEmpty()
        }
        val price = when {
            free.containsMatchIn(content) -> 0
            else -> rub.findAll(content).mapNotNull { it.groupValues[1].filter(Char::isDigit).toIntOrNull() }.filter { it in 50..20_000 }.minOrNull()
        }
        return listOf(concert(channel, post, title, date, time, venue, address, price, link, image, content))
    }

    private fun concert(
        channel: Channel, id: String, title: String, date: LocalDate, time: LocalTime?, venue: String,
        address: String, price: Int?, link: String, image: String?, text: String,
    ) = Concert(
        id = "tg:$id",
        title = title,
        start = date.atTime(time ?: LocalTime.NOON).atZone(Concert.ZONE).toInstant().toEpochMilli(),
        venue = venue,
        locality = "Санкт-Петербург",
        address = address,
        price = price,
        // Posts name no genre field: the words of the post decide (Genres maps them to families).
        genres = Genres.families(listOf(text)).toList(),
        imageUrl = image,
        url = link,
        sources = listOf("Telegram · ${channel.label}"),
        timeKnown = time != null,
    )

    /** The next [day].[month] from [today] (a poster announces what is coming), or null if impossible. */
    private fun dateOf(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val y = year?.let { if (it < 100) 2000 + it else it }
        val candidate = runCatching { LocalDate.of(y ?: today.year, month, day) }.getOrNull() ?: return null
        val date = if (y == null && candidate.isBefore(today.minusDays(1))) candidate.plusYears(1) else candidate
        return date.takeIf { !it.isBefore(today) && it.isBefore(today.plusDays(120)) }
    }

    /** Emojis and decoration off; what is left of a name. */
    private fun clean(s: String): String =
        s.replace(Regex("""[^\p{L}\p{N}\s'’&.,!?:()«»"+/-]"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '|', '-', '—', '·', ',', '.')

    private const val TITLE_MAX = 70

    private val numericEntity = Regex("""&#(\d+);""")

    private fun htmlDecode(s: String): String =
        s.replace(numericEntity) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
}
