package com.metrolist.music.ui.theme

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.utils.DownloadExportState
import com.metrolist.music.db.entities.ListenStats
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class TmuxTab(
    val label: String,
    val selected: Boolean,
    val onClick: (selected: Boolean) -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)

/**
 * The Linux theme's bottom navigation: a tmux status line — numbered windows, the current one
 * highlighted and starred.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TmuxStatusBar(
    items: List<TmuxTab>,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .background(containerColor)
            .windowInsetsPadding(NavigationBarDefaults.windowInsets),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEachIndexed { index, tab ->
                    val label = "$index:${tab.label.lowercase()}${if (tab.selected) "*" else ""}"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(0.62f)
                            .background(if (tab.selected) colors.primary else Color.Transparent)
                            .combinedClickable(
                                onClick = { tab.onClick(tab.selected) },
                                onLongClick = tab.onLongClick?.let { long ->
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        long()
                                    }
                                },
                            ),
                    ) {
                        Text(
                            text = label,
                            style = Mono.copy(fontSize = 13.sp, fontWeight = if (tab.selected) FontWeight.Bold else FontWeight.Normal),
                            color = if (tab.selected) colors.onPrimary else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private val Logo = listOf(
    "    ▄▄████████▄▄   ",
    "  ▄██▀▀      ▀▀██▄ ",
    " ▀▀  ▄▄██████▄▄  ▀▀",
    "   ▄██▀▀    ▀▀██▄  ",
    "    ▀  ▄████▄  ▀   ",
    "       ▀▀██▀▀      ",
    "         ▀▀        ",
)

/**
 * A neofetch-style block for the top of the home page in the Linux theme: the logo and a few facts
 * about listening — this session, today, all time, the week's top artist — plus the library and the "system".
 */
@Composable
fun LinuxFetch(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val database = LocalDatabase.current
    val liked by remember(database) { database.likedSongsCount() }.collectAsState(initial = 0)
    val downloaded by DownloadExportState.exported.collectAsState()
    // Event times are local wall-clock millis stored as UTC (see Converters).
    fun wallMillis(time: LocalDateTime) = time.toInstant(ZoneOffset.UTC).toEpochMilli()
    val sessionStart = remember {
        // This run of the app: the process start, as wall-clock time.
        val sinceStart = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        wallMillis(LocalDateTime.now().minusNanos(sinceStart * 1_000_000))
    }
    val todayStart = remember { wallMillis(LocalDate.now().atStartOfDay()) }
    val weekStart = remember { wallMillis(LocalDate.now().minusDays(7).atStartOfDay()) }
    val session by remember(database) { database.listenStats(sessionStart) }.collectAsState(initial = ListenStats(0, 0))
    val today by remember(database) { database.listenStats(todayStart) }.collectAsState(initial = ListenStats(0, 0))
    val total by remember(database) { database.listenStats(0L) }.collectAsState(initial = ListenStats(0, 0))
    val topArtist by remember(database) { database.mostPlayedArtists(weekStart, limit = 1) }.collectAsState(initial = emptyList())
    val playing = LocalPlayerConnection.current?.mediaMetadata?.collectAsState()?.value
    val dark = colors.surface.run { (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f }
    val label = colors.primary
    val value = colors.onSurface
    val logoColors = listOf(Gruvbox.yellow, Gruvbox.yellow, Gruvbox.orange, Gruvbox.orange, Gruvbox.red, Gruvbox.red, Gruvbox.red)
        .map { if (dark) it else it.darker() }
    val host = Build.DEVICE.lowercase()
    val uptime = remember {
        val minutes = SystemClock.elapsedRealtime() / 60_000
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
    fun ListenStats.line() = "$plays · ${playTime.asDuration()}"
    val facts = listOf(
        "session" to session.line(),
        "today" to today.line(),
        "total" to total.line(),
        "top 7d" to (topArtist.firstOrNull()?.artist?.name ?: "—"),
        "library" to "$liked liked · ${downloaded.size} local",
        "np" to (playing?.let { "${it.artists.joinToString { a -> a.name }} – ${it.title}" } ?: "—"),
        "sys" to "Drxwnify ${BuildConfig.VERSION_NAME} · android ${Build.VERSION.RELEASE} · ${Build.MODEL}",
        "uptime" to uptime,
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(1.dp, colors.outline)
            .padding(10.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.secondary)) { append("you@$host") }
                withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(":") }
                withStyle(SpanStyle(color = colors.tertiary)) { append("~") }
                withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append("$ ") }
                withStyle(SpanStyle(color = value)) { append("drxfetch") }
            },
            style = Mono.copy(fontSize = 12.sp),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Column {
                Logo.forEachIndexed { i, line ->
                    Text(line, style = Mono.copy(fontSize = 9.sp, lineHeight = 11.sp), color = logoColors[i])
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "you@drxwnify",
                    style = Mono.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    color = label,
                )
                Text("─".repeat(12), style = Mono.copy(fontSize = 12.sp), color = colors.onSurfaceVariant)
                facts.forEach { (k, v) ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = label, fontWeight = FontWeight.Bold)) { append(k.padEnd(8)) }
                            withStyle(SpanStyle(color = value)) { append(v) }
                        },
                        style = Mono.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(
                        Color(0xFF282828), Gruvbox.red, Gruvbox.green, Gruvbox.yellow,
                        Gruvbox.blue, Gruvbox.purple, Gruvbox.aqua, Color(0xFFEBDBB2),
                    ).forEach { Box(Modifier.size(width = 16.dp, height = 10.dp).background(it)) }
                }
            }
        }
    }
}

private fun Color.darker() = Color(red * 0.7f, green * 0.7f, blue * 0.7f, alpha)

/** "47m", "3h 12m", "312h". */
private fun Long.asDuration(): String {
    val minutes = this / 60_000
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 100 * 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / 60}h"
    }
}
