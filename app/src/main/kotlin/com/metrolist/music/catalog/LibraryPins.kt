/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Library items pinned to the top with a long press (as friends are), on this phone only: the
 * account's own pins (Spotify) stay as they are, these come first, in the order they were pinned.
 */
object LibraryPins {
    private var prefs: SharedPreferences? = null
    private val _pinned = MutableStateFlow<List<String>>(emptyList())
    val pinned: StateFlow<List<String>> = _pinned

    fun init(context: Context) {
        val p = context.getSharedPreferences("library_pins", Context.MODE_PRIVATE)
        prefs = p
        _pinned.value = runCatching {
            JSONArray(p.getString("pinned", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
        }.getOrDefault(emptyList())
    }

    /** Pins [key] (a library entry's uri or id), or unpins it; returns whether it is pinned now. */
    fun toggle(key: String): Boolean {
        val now = _pinned.value
        val updated = if (key in now) now - key else now + key
        _pinned.value = updated
        prefs?.edit()?.putString("pinned", JSONArray(updated).toString())?.apply()
        return key in updated
    }
}
