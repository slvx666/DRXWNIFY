/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.concerts

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Calendar
import java.util.UUID

/**
 * "Tell me about gigs like this": every criterion that is set must match (price, venues, genres —
 * several genres/venues mean any of them), a rule without criteria matches nothing.
 */
data class ConcertRule(
    val id: String = UUID.randomUUID().toString(),
    val maxPrice: Int? = null,
    val freeOnly: Boolean = false,
    val genres: Set<String> = emptySet(),
    val venues: Set<String> = emptySet(),
    val enabled: Boolean = true,
) {
    val hasCriteria get() = freeOnly || maxPrice != null || genres.isNotEmpty() || venues.isNotEmpty()

    fun matches(c: Concert): Boolean {
        if (!hasCriteria) return false
        if (freeOnly && !c.isFree) return false
        // Unknown price never satisfies a price limit — better to miss a gig than to send a false hope.
        if (maxPrice != null && (c.price == null || c.price > maxPrice)) return false
        if (genres.isNotEmpty() && c.families.none { it in genres }) return false
        if (venues.isNotEmpty() && venues.none { it.equals(c.venue, true) }) return false
        return true
    }
}

/**
 * Two alerts per matching gig — a week before (exactly seven days) and on the day itself — sent
 * from a daily inexact alarm at about 10:00, so the poster needs no service of its own. What was
 * already announced is remembered so a refresh never repeats a notification.
 */
object ConcertAlerts {
    private const val PREFS = "concerts"
    private const val CHANNEL = "concerts"
    private const val ACTION = "com.metrolist.music.CONCERT_ALERTS"
    private const val HOUR = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- rules --------------------------------------------------------------------------------

    private val _rules = MutableStateFlow<List<ConcertRule>?>(null)

    fun rulesFlow(context: Context): StateFlow<List<ConcertRule>?> {
        if (_rules.value == null) _rules.value = load(context)
        return _rules
    }

    fun rules(context: Context): List<ConcertRule> = load(context)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): List<ConcertRule> {
        val text = prefs(context).getString("rules", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConcertRule(
                    id = o.getString("id"),
                    maxPrice = if (o.isNull("p")) null else o.getInt("p"),
                    freeOnly = o.optBoolean("f"),
                    genres = o.optJSONArray("g")?.let { g -> (0 until g.length()).map { g.getString(it) }.toSet() }.orEmpty(),
                    venues = o.optJSONArray("v")?.let { g -> (0 until g.length()).map { g.getString(it) }.toSet() }.orEmpty(),
                    enabled = o.optBoolean("e", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveRules(context: Context, rules: List<ConcertRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject().put("id", r.id).put("p", r.maxPrice ?: JSONObject.NULL).put("f", r.freeOnly)
                    .put("g", JSONArray(r.genres.toList())).put("v", JSONArray(r.venues.toList())).put("e", r.enabled),
            )
        }
        prefs(context).edit().putString("rules", arr.toString()).apply()
        _rules.value = rules
        if (rules.any { it.enabled && it.hasCriteria } && Concerts.isEnabled(context)) schedule(context) else cancel(context)
    }

    // ---- alarm --------------------------------------------------------------------------------

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 7321,
            Intent(context, ConcertAlertReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The next ~10:00. Called after boot / app update / every alarm, and when rules change. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        if (!Concerts.isEnabled(app) || load(app).none { it.enabled && it.hasCriteria }) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, (0..20).random()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app)) }
            .onFailure { Timber.w(it, "ConcertAlerts: schedule failed") }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pending(app))
    }

    // ---- checking -----------------------------------------------------------------------------

    /** Gigs of [rules] a week from today and today, minus what was announced before. */
    internal fun due(context: Context, all: List<Concert>, rules: List<ConcertRule>): Pair<List<Concert>, List<Concert>> {
        val sent = prefs(context).getStringSet("sent", emptySet()).orEmpty()
        val today = java.time.LocalDate.now(Concert.ZONE)
        val active = rules.filter { it.enabled && it.hasCriteria }
        fun pick(day: java.time.LocalDate, kind: String) = all
            .filter { it.date == day && active.any { r -> r.matches(it) } && "$kind:${it.id}" !in sent }
        return pick(today.plusDays(7), "w") to pick(today, "d")
    }

    suspend fun check(context: Context) {
        val app = context.applicationContext
        if (!Concerts.isEnabled(app)) return
        val rules = load(app)
        if (rules.none { it.enabled && it.hasCriteria }) return
        val all = Concerts.load(app)
        val (week, day) = due(app, all, rules)
        if (week.isEmpty() && day.isEmpty()) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.concerts_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (!nm.areNotificationsEnabled()) return
        notify(app, nm, 4101, app.getString(R.string.concerts_notif_today), day)
        notify(app, nm, 4102, app.getString(R.string.concerts_notif_week), week)
        val sent = prefs(app).getStringSet("sent", emptySet()).orEmpty().toMutableSet()
        day.forEach { sent.add("d:${it.id}") }
        week.forEach { sent.add("w:${it.id}") }
        // Old ids are harmless but the set must not grow for ever.
        prefs(app).edit().putStringSet("sent", sent.toList().takeLast(400).toSet()).apply()
    }

    private fun notify(context: Context, nm: NotificationManager, id: Int, title: String, list: List<Concert>) {
        if (list.isEmpty()) return
        fun line(c: Concert): String {
            val t = java.time.Instant.ofEpochMilli(c.start).atZone(Concert.ZONE).toLocalTime()
            val price = when {
                c.isFree -> " · " + context.getString(R.string.concerts_free)
                c.price != null -> " · ${c.price} ₽"
                else -> ""
            }
            return "${c.title} — ${c.venue}, %02d:%02d$price".format(t.hour, t.minute)
        }
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "concerts")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = NotificationCompat.InboxStyle().also { s -> list.take(6).forEach { s.addLine(line(it)) } }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.calendar_month)
            .setContentTitle(title)
            .setContentText(if (list.size == 1) line(list[0]) else context.getString(R.string.concerts_notif_many, list.size))
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    internal fun runCheck(context: Context, then: () -> Unit) {
        scope.launch {
            try {
                check(context)
            } catch (e: Exception) {
                Timber.w(e, "ConcertAlerts: check failed")
            } finally {
                then()
            }
        }
    }
}

class ConcertAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                ConcertAlerts.schedule(app)
            }
            else -> {
                // Schedule the next day first: if the check dies the chain survives.
                ConcertAlerts.schedule(app)
                val pending = goAsync()
                ConcertAlerts.runCheck(app) { pending.finish() }
            }
        }
    }
}
