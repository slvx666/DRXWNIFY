/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import com.metrolist.spotify.models.SpotifySavedTrack
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The main account's whole Liked Songs, read in the background, page after page with a pause
 * between them, into [LikedSongsCache] — for those who never open the list: the dice and the
 * "For you" covers draw from every like, not only the latest few hundred, and the list opens at once
 * when it is opened. Once read, a later run asks only for the first page to see what changed.
 */
object LikedSongsSync {
    private const val PAGE = 50
    private const val PAGE_PAUSE_MS = 700L
    private const val RETRIES = 3

    /** After the app starts: the home page and the first track come first. */
    private const val START_DELAY_MS = 20_000L

    /** Not more often than this per account in one run. */
    private const val CHECK_EVERY_MS = 3 * 60 * 60_000L

    /** Far past any real library: a safety stop. */
    private const val MAX_TRACKS = 20_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null

    @Volatile private var lastDone: Pair<String, Long>? = null

    /** Reads the list in the background unless it is being read or was checked lately. */
    fun start(delayMs: Long = START_DELAY_MS) {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(delayMs)
            Catalog.awaitAccounts()
            val account = Catalog.source?.name ?: return@launch
            lastDone?.takeIf { it.first == account && System.currentTimeMillis() - it.second < CHECK_EVERY_MS }
                ?.let { return@launch }
            if (!Catalog.ensureAuthenticated()) return@launch
            val ok = try {
                sync(account)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "LikedSongsSync: stopped")
                false
            }
            if (ok) lastDone = account to System.currentTimeMillis()
        }
    }

    private suspend fun sync(account: String): Boolean {
        val cached = LikedSongsCache.get(account)?.takeIf { it.tracks.isNotEmpty() }
        if (cached != null) {
            if (cached.ageMs < LikedSongsCache.FRESH_MS) return true
            if (upToDate(account, cached)) return true
        }
        return readAll(account)
    }

    /**
     * Compares the first page with the kept list (as the Liked Songs screen does): unchanged, or
     * only new likes on top, needs nothing more. False when the whole list must be read again.
     */
    private suspend fun upToDate(account: String, cached: LikedSongsCache.Cached): Boolean {
        val page = page(0) ?: return true // no network: asked again next time
        val first = page.items.map { it.track }.filter { !it.isLocal }
        val firstIds = first.map { it.id }
        val keptIds = cached.tracks.map { it.id }
        val added = page.total - cached.total
        return when {
            added == 0 && keptIds.take(firstIds.size) == firstIds -> {
                LikedSongsCache.touch(account)
                true
            }
            added in 1..firstIds.size && keptIds.take(firstIds.size - added) == firstIds.drop(added) -> {
                LikedSongsCache.put(account, first.take(added) + cached.tracks, page.total, LikedSongsCache.addedAt(account) + addedOf(page.items))
                com.metrolist.music.playback.ForYouRecommender.likesChanged()
                true
            }
            else -> false
        }
    }

    /** Every page, one at a time; kept only when complete (the screen trusts a kept list as whole). */
    private suspend fun readAll(account: String): Boolean {
        val tracks = ArrayList<SpotifyTrack>()
        val added = HashMap<String, Long>()
        var offset = 0
        var total = 0
        do {
            // The main account changed meanwhile: this list is not its.
            if (Catalog.source?.name != account) return false
            val page = page(offset) ?: return false
            total = page.total
            tracks += page.items.map { it.track }.filter { !it.isLocal }
            added += addedOf(page.items)
            offset += PAGE
            if (page.items.isEmpty()) break
            if (offset < total) delay(PAGE_PAUSE_MS)
        } while (offset < total && offset < MAX_TRACKS)
        LikedSongsCache.put(account, tracks, total, LikedSongsCache.addedAt(account) + added)
        Timber.i("LikedSongsSync: %d liked songs kept for %s", tracks.size, account)
        com.metrolist.music.playback.ForYouRecommender.likesChanged()
        return true
    }

    private suspend fun page(offset: Int): com.metrolist.spotify.models.SpotifyPaging<SpotifySavedTrack>? {
        repeat(RETRIES) { attempt ->
            Catalog.likedSongs(limit = PAGE, offset = offset).getOrNull()?.let { return it }
            delay(2_000L shl attempt)
        }
        return null
    }

    private fun addedOf(items: List<SpotifySavedTrack>): Map<String, Long> =
        items.mapNotNull { saved ->
            com.metrolist.music.portrait.PortraitBuilder.parseTime(saved.addedAt)?.let { saved.track.id to it }
        }.toMap()
}
