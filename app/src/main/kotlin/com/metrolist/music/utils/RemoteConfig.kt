/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Settings the app fetches from the internet, so what dies outside the app (a mirror, a shared
 * account) is fixed by editing one JSON file instead of shipping a release.
 *
 * Tried in order: the address set in Settings → Music sources (for testing a new file), then
 * [BUILT_IN_URLS] — several hosts, so one of them going down (or a GitHub account being flagged)
 * breaks nothing. The last good copy is kept and used until a newer one arrives.
 *
 * Format:
 * ```
 * {
 *   "version": 1,
 *   "vkShared": { "token": "…", "dailyTracks": 40 },         // for users without their own VK
 *   "losslessMirrors": ["https://…", "https://…"],           // hifi-api compatible instances
 *   "disabledSources": ["SOULSEEK"]                           // emergency switch
 * }
 * ```
 */
object RemoteConfig {
    /** Where the config lives. Filled in once the new GitHub account exists (several hosts). */
    val BUILT_IN_URLS: List<String> = emptyList()

    val UrlOverrideKey = stringPreferencesKey("remoteConfigUrl")
    private val CachedKey = stringPreferencesKey("remoteConfigCache")

    data class VkShared(val token: String, val dailyTracks: Int)

    data class Config(
        val vkShared: VkShared? = null,
        val losslessMirrors: List<String> = emptyList(),
        val disabledSources: Set<String> = emptySet(),
    )

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    }

    private const val REFRESH_MS = 6L * 60 * 60 * 1000

    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            app.dataStore.get(CachedKey, "").takeIf { it.isNotBlank() }?.let { cached ->
                parse(cached)?.let { _config.value = it }
            }
            while (isActive) {
                refresh(app)
                delay(REFRESH_MS)
            }
        }
    }

    /** Fetches the config now; true when a copy was loaded. */
    suspend fun refresh(context: Context): Boolean {
        val override = context.dataStore.get(UrlOverrideKey, "").trim()
        val urls = listOfNotNull(override.takeIf { it.isNotBlank() }) + BUILT_IN_URLS
        for (url in urls) {
            val text = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.onFailure { Timber.d("RemoteConfig: %s unreachable: %s", url, it.message) }.getOrNull() ?: continue
            val parsed = parse(text) ?: continue
            _config.value = parsed
            context.dataStore.edit { it[CachedKey] = text }
            Timber.i("RemoteConfig: loaded from %s", url)
            return true
        }
        return false
    }

    private fun parse(text: String): Config? = runCatching {
        val root = JSONObject(text)
        val vk = root.optJSONObject("vkShared")?.let { o ->
            o.optString("token").takeIf { it.isNotBlank() }?.let { VkShared(it, o.optInt("dailyTracks", 40)) }
        }
        val mirrors = root.optJSONArray("losslessMirrors")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).trim().trimEnd('/').takeIf { u -> u.startsWith("https://") } }
        }.orEmpty()
        val disabled = root.optJSONArray("disabledSources")?.let { a ->
            (0 until a.length()).map { a.optString(it).uppercase() }.toSet()
        }.orEmpty()
        Config(vk, mirrors, disabled)
    }.onFailure { Timber.w(it, "RemoteConfig: bad JSON") }.getOrNull()
}
