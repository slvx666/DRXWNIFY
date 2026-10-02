/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.music.utils.RemoteConfig

/**
 * A shared VK account (from [RemoteConfig]) for people who haven't linked their own, so they can
 * hear what VK adds before signing in. Kept safe for the account:
 *  - only listening: nothing is ever written to it (no likes, no saved albums, no library shown);
 *  - a daily number of tracks per phone ([RemoteConfig.VkShared.dailyTracks]);
 *  - the VK calls themselves are spaced out by the provider as for any account.
 * The config can swap or withdraw the token at any time (a banned account).
 */
object SharedVk {
    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("shared_vk", Context.MODE_PRIVATE)
    }

    /** The token to use: the user's own, else the shared one when the config offers it. */
    fun tokenFor(userToken: String?): String? =
        userToken?.takeIf { it.isNotBlank() } ?: RemoteConfig.config.value.vkShared?.token

    /** True while the shared account stands in for the user's own. */
    fun inUse(userToken: String?): Boolean = userToken.isNullOrBlank() && RemoteConfig.config.value.vkShared != null

    /** Counts one track played or downloaded through the shared account; false once today's are used up. */
    fun allowTrack(userToken: String?): Boolean {
        if (!inUse(userToken)) return true
        val store = prefs ?: return true
        val limit = RemoteConfig.config.value.vkShared?.dailyTracks ?: return true
        val today = (System.currentTimeMillis() / DAY_MS).toInt()
        val used = if (store.getInt("day", -1) == today) store.getInt("count", 0) else 0
        if (used >= limit) return false
        store.edit().putInt("day", today).putInt("count", used + 1).apply()
        return true
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
