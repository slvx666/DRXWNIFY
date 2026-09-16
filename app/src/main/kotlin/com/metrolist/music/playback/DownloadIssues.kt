/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why a catalog track (by its Spotify / Yandex id) didn't end up downloaded the last time a batch
 * tried, so lists can show it next to the track and the user knows what to do about it. Stored on
 * the device only; cleared as soon as the track downloads.
 */
object DownloadIssues {
    enum class Reason {
        /** No enabled audio source had this track. */
        NOT_FOUND,

        /** The audio was found but downloading it failed. */
        DOWNLOAD_FAILED,

        /** Downloaded, but writing the file into the folder failed. */
        SAVE_FAILED,
    }

    private const val PREFS = "download_issues"

    private val _issues = MutableStateFlow<Map<String, Reason>>(emptyMap())
    val issues: StateFlow<Map<String, Reason>> = _issues.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _issues.value = prefs.all.mapNotNull { (id, value) ->
                val reason = (value as? String)?.let { name -> Reason.entries.firstOrNull { it.name == name } }
                reason?.let { id to it }
            }.toMap()
            loaded = true
        }
    }

    fun set(context: Context, trackId: String, reason: Reason) {
        if (trackId.isBlank()) return
        ensureLoaded(context)
        if (_issues.value[trackId] == reason) return
        _issues.value = _issues.value + (trackId to reason)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(trackId, reason.name).apply()
    }

    fun clear(context: Context, trackIds: Collection<String>) {
        ensureLoaded(context)
        val present = trackIds.filter { it in _issues.value }
        if (present.isEmpty()) return
        _issues.value = _issues.value - present.toSet()
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        present.forEach { editor.remove(it) }
        editor.apply()
    }
}
