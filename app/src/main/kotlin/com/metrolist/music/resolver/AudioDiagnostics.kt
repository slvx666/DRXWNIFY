/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Human-readable journal of how audio was found for each track: which providers answered, how fast,
 * why one failed, which stream was played or downloaded. Kept in memory (last [MAX_LINES] lines) for
 * the in-app "Audio search log" screen AND written to logcat with tag [TAG] via android.util.Log.i/w,
 * which release builds keep (`adb logcat -s MeldAudio`).
 */
object AudioDiagnostics {
    const val TAG = "DrxwAudio"
    private const val MAX_LINES = 600

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun info(message: String) = add('I', message)

    fun warn(message: String) = add('W', message)

    private fun add(level: Char, message: String) {
        runCatching {
            if (level == 'W') Log.w(TAG, message) else Log.i(TAG, message)
        }
        val line = "${timeFormat.get()!!.format(Date())} $level $message"
        synchronized(this) {
            val current = _lines.value
            _lines.value = if (current.size >= MAX_LINES) current.drop(current.size - MAX_LINES + 1) + line else current + line
        }
        file?.let { f -> writer.execute { runCatching { f.appendText(line + NEWLINE) } } }
    }

    /**
     * The journal is kept in a file as well: a slow start reported hours later used to be gone with
     * the app's process (and the system log only holds a minute), so there was nothing to look at.
     */
    @Volatile
    private var file: java.io.File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private const val NEWLINE = "\n"

    fun init(context: android.content.Context) {
        if (file != null) return
        val f = java.io.File(context.filesDir, "audio_log.txt")
        writer.execute {
            runCatching {
                val kept = if (f.exists()) f.readLines().takeLast(MAX_LINES) else emptyList()
                // Trimmed once per start, so the file never grows past a few hundred KB.
                f.writeText(kept.joinToString(NEWLINE, postfix = if (kept.isEmpty()) "" else NEWLINE))
                synchronized(this) { _lines.value = (kept + _lines.value).takeLast(MAX_LINES) }
            }
            file = f
        }
    }

    /** Why audio could not be found for a track — turned into a short explanation on the player cover. */
    enum class FailureKind {
        /** No enabled source had the track. */
        NOT_FOUND,

        /** Every audio source is switched off. */
        ALL_SOURCES_DISABLED,

        /** No catalog metadata to search other sources with (plain YouTube item). */
        NO_METADATA,
    }

    data class TrackProblems(
        val failure: FailureKind? = null,
        /** YouTube refused the video (age restriction, region, removed). */
        val youtubeRestricted: Boolean = false,
        /** YouTube could not be reached at all (network / VPN). */
        val youtubeUnreachable: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    private val problems = java.util.concurrent.ConcurrentHashMap<String, TrackProblems>()

    private fun update(mediaId: String, change: (TrackProblems) -> TrackProblems) {
        problems.compute(mediaId) { _, old -> change(old ?: TrackProblems()).copy(at = System.currentTimeMillis()) }
        if (problems.size > 500) problems.entries.minByOrNull { it.value.at }?.let { problems.remove(it.key) }
    }

    fun recordFailure(mediaId: String, kind: FailureKind) = update(mediaId) { it.copy(failure = kind) }

    fun recordYouTubeRestricted(mediaId: String) = update(mediaId) { it.copy(youtubeRestricted = true) }

    fun recordYouTubeUnreachable(mediaId: String) = update(mediaId) { it.copy(youtubeUnreachable = true) }

    /** Problems recorded for [mediaId] in the last 30 minutes. */
    fun problemsFor(mediaId: String?): TrackProblems? =
        mediaId?.let { problems[it] }?.takeIf { System.currentTimeMillis() - it.at < 30 * 60 * 1000L }

    fun clearProblems(mediaId: String) {
        problems.remove(mediaId)
    }

    fun clear() {
        synchronized(this) { _lines.value = emptyList() }
        file?.let { f -> writer.execute { runCatching { f.writeText("") } } }
    }

    fun asText(): String = _lines.value.joinToString("\n")

    /**
     * An error as it is worth reading in a log: the exception chain (class and message of each
     * cause, "failed: null" said nothing) and, for the usual network troubles, what they mean for
     * the user (no internet, a VPN in the way, a blocked service) or that it was no error at all.
     */
    fun explain(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(4).toList()
        val text = chain.joinToString(" ← ") { e ->
            e.javaClass.simpleName + (e.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(180) } ?: "") +
                // Media3's own codes say what broke when the message is empty.
                ((e as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let { " (HTTP ${it.responseCode})" }
                    ?: (e as? androidx.media3.datasource.DataSourceException)?.let { " (code ${it.reason})" } ?: "")
        }
        val meaning = chain.firstNotNullOfOrNull { e ->
            when (e) {
                is kotlinx.coroutines.TimeoutCancellationException -> "gave up waiting — slow network or VPN, or every source was slow"
                is java.net.UnknownHostException -> "host not found — no internet, or the service is blocked here (try with/without VPN)"
                is java.net.SocketTimeoutException -> "timed out — slow or unstable network / VPN"
                is java.net.ConnectException -> "connection refused — the server is blocked from this network (VPN?) or down"
                is java.net.NoRouteToHostException -> "no route — the network or VPN is down"
                is javax.net.ssl.SSLException -> "TLS failed — a VPN/proxy or antivirus is interfering, or the phone's clock is wrong"
                is java.io.InterruptedIOException, is InterruptedException,
                is kotlinx.coroutines.CancellationException -> "cancelled (track switched, app closed) — not an error"
                else -> null
            }
        }
        return if (meaning != null) "$text [$meaning]" else text
    }

    /**
     * The header of a shared log: what the app and the phone were set to. Many reports are answered
     * by it alone (all sources off, no VPN while a service is blocked, the shared VK account used up).
     */
    fun environment(context: android.content.Context): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Drxwnify $version · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "NO NETWORK"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val vpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            val validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            appendLine("network: $net · VPN ${if (vpn) "on" else "off"} · internet ${if (validated) "checked OK" else "NOT confirmed"}")
        }
        val sources = AudioProviderId.entries.filter { it != AudioProviderId.QOBUZ }.joinToString { id ->
            id.name.lowercase() + if (ResolverPreferences.isEnabled(id)) "" else " (off)"
        }
        appendLine("sources: $sources · order ${ResolverPreferences.order.joinToString(">") { it.name.lowercase() }}")
        appendLine("source choice: ${ResolverPreferences.pickMode}")
        appendLine("VK: ${if (VkMusic.ownAccount) "own account" else "shared demo account"} · ready ${VkMusic.isReady}")
        runCatching { appendLine("free space: ${context.filesDir.usableSpace / (1024 * 1024)} MB") }
    }
}
