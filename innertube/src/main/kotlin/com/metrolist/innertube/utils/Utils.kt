package com.metrolist.innertube.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.PlaylistPage
import java.security.MessageDigest

/**
 * The whole playlist, every continuation followed. A continuation that fails is asked again twice
 * (one dropped request used to end a long "Liked music" part-way), and the limit is high enough for
 * tens of thousands of tracks.
 */
@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(): Result<PlaylistPage> = completedWithStatus().map { it.first }

/** [completed], and whether the end was really reached (false: it stopped part-way). */
suspend fun Result<PlaylistPage>.completedWithStatus(): Result<Pair<PlaylistPage, Boolean>> = runCatching {
    val page = getOrThrow()
    val songs = page.songs.toMutableList()
    var continuation = page.songsContinuation
    val seenContinuations = mutableSetOf<String>()
    var requestCount = 0
    val maxRequests = 500
    var consecutiveEmptyResponses = 0
    var complete = true

    while (continuation != null) {
        if (requestCount >= maxRequests) {
            complete = false
            break
        }
        if (continuation in seenContinuations) {
            break
        }
        seenContinuations.add(continuation)
        requestCount++

        var continuationPage = YouTube.playlistContinuation(continuation).getOrNull()
        var retry = 0
        while (continuationPage == null && retry < 2) {
            kotlinx.coroutines.delay(1_000L shl retry)
            retry++
            continuationPage = YouTube.playlistContinuation(continuation).getOrNull()
        }
        if (continuationPage == null) {
            complete = false
            break
        }

        if (continuationPage.songs.isEmpty()) {
            consecutiveEmptyResponses++
            if (consecutiveEmptyResponses >= 2) break
        } else {
            consecutiveEmptyResponses = 0
            songs += continuationPage.songs
        }

        continuation = continuationPage.continuation
    }
    PlaylistPage(
        playlist = page.playlist,
        songs = songs.distinctBy { it.id },
        songsContinuation = null,
        continuation = page.continuation
    ) to complete
}

@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(): Result<LibraryPage> = runCatching {
    val page = getOrThrow()
    val items = page.items.toMutableList()
    var continuation = page.continuation
    val seenContinuations = mutableSetOf<String>()
    var requestCount = 0
    val maxRequests = 50
    var consecutiveEmptyResponses = 0
    
    while (continuation != null && requestCount < maxRequests) {
        if (continuation in seenContinuations) {
            break
        }
        seenContinuations.add(continuation)
        requestCount++
        
        val continuationPage = YouTube.libraryContinuation(continuation).getOrNull() ?: break
        
        if (continuationPage.items.isEmpty()) {
            consecutiveEmptyResponses++
            if (consecutiveEmptyResponses >= 2) break
        } else {
            consecutiveEmptyResponses = 0
            items += continuationPage.items
        }
        
        continuation = continuationPage.continuation
    }
    LibraryPage(
        items = items,
        continuation = null
    )
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split("; ")
        .filter { it.isNotEmpty() }
        .mapNotNull { part ->
            val splitIndex = part.indexOf('=')
            if (splitIndex == -1) null
            else part.substring(0, splitIndex) to part.substring(splitIndex + 1)
        }
        .toMap()

fun String.parseTime(): Int? {
    try {
        val parts = split(":").map { it.toInt() }
        if (parts.size == 2) {
            return parts[0] * 60 + parts[1]
        }
        if (parts.size == 3) {
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}
