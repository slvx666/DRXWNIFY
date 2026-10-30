package com.metrolist.music.concerts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TelegramPostersTest {
    private fun page(name: String) = javaClass.classLoader!!.getResource("telegram/$name.html")!!.readText()

    private val message = Regex("""<div class="tgme_widget_message [^"]*"[^>]*data-post="([^"]+)"(.*?)(?=<div class="tgme_widget_message_wrap|\z)""", RegexOption.DOT_MATCHES_ALL)

    private fun parse(name: String): List<Concert> {
        val channel = TelegramPosters.CHANNELS.first { it.name == name }
        return message.findAll(page(name)).flatMap {
            TelegramPosters.parsePost(channel, it.groupValues[1], it.groupValues[2], LocalDate.of(2026, 10, 30))
        }.toList()
    }

    @Test
    fun channelsGiveGigs() {
        val report = StringBuilder()
        for (name in listOf("spbgigs", "gigsspb", "golyiwood", "ionotekaspb")) {
            val gigs = parse(name)
            report.appendLine("== $name: ${gigs.size}")
            gigs.forEach { report.appendLine("  ${it.date} ${if (it.timeKnown) java.time.Instant.ofEpochMilli(it.start).atZone(Concert.ZONE).toLocalTime() else "--:--"} | ${it.title} @ ${it.venue} | ${it.address} | ${it.price} | ${it.genres} | ${it.url}") }
        }
        java.io.File(System.getProperty("java.io.tmpdir"), "tg_report.txt").writeText(report.toString())
        assertTrue(parse("spbgigs").isNotEmpty())
    }
}
