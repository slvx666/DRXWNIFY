/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.catalog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.constants.MetadataSource
import com.metrolist.music.constants.PrimaryMetadataSourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The main music account and what goes with the linked ones:
 *  - which account is the main one (library, home, recommendations, colour, profile);
 *  - whether another linked account shows as a tab of its own in the library (off by default);
 *  - where a like goes ([LikeMode]).
 *
 * A re-login never changes the main account: only linking an account for the first time may
 * (Spotify by default; another service only when there is no main account yet), and the user's
 * own choice on the account's page.
 */
object MainAccount {
    /** Where a heart goes. */
    enum class LikeMode {
        /** Only into the main account. */
        MAIN_ONLY,

        /** The main account and the service the track came from (the default). */
        MAIN_AND_SOURCE,

        /** Every linked account that has the track. */
        ALL,
    }

    val LikeModeKey = stringPreferencesKey("likeMode")

    fun tabKey(source: MetadataSource) = booleanPreferencesKey("accountTab_${source.name}")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var dataStore: DataStore<Preferences>? = null

    private val _likeMode = MutableStateFlow(LikeMode.MAIN_AND_SOURCE)
    val likeMode: StateFlow<LikeMode> = _likeMode

    private val _tabs = MutableStateFlow<Set<MetadataSource>>(emptySet())

    /** Linked accounts (other than the main one) the user wants as library tabs. */
    val tabs: StateFlow<Set<MetadataSource>> = _tabs

    fun init(store: DataStore<Preferences>) {
        dataStore = store
        scope.launch {
            store.data.collect { prefs ->
                _likeMode.value = prefs[LikeModeKey]?.let { v -> LikeMode.entries.firstOrNull { it.name == v } } ?: LikeMode.MAIN_AND_SOURCE
                _tabs.value = MetadataSource.entries.filter { prefs[tabKey(it)] == true }.toSet()
            }
        }
    }

    fun tabShown(source: MetadataSource): Flow<Boolean> =
        dataStore?.data?.map { it[tabKey(source)] == true } ?: kotlinx.coroutines.flow.flowOf(false)

    fun setTab(source: MetadataSource, shown: Boolean) {
        val store = dataStore ?: return
        _tabs.value = if (shown) _tabs.value + source else _tabs.value - source
        scope.launch { store.edit { it[tabKey(source)] = shown } }
    }

    fun setLikeMode(mode: LikeMode) {
        val store = dataStore ?: return
        _likeMode.value = mode
        scope.launch { store.edit { it[LikeModeKey] = mode.name } }
    }

    /**
     * Makes [source] the main account. Nothing is deleted: the old main account's likes stay in it
     * (and in its tab, when shown); the screens that depend on the main account start over.
     */
    fun choose(source: MetadataSource) {
        val store = dataStore ?: return
        if (Catalog.state.value.source == source && Catalog.state.value.preferred == source) return
        scope.launch {
            store.edit { it[PrimaryMetadataSourceKey] = source.name }
            Catalog.invalidateCaches()
            com.metrolist.music.home.HomeFeed.markStale()
            runCatching { com.metrolist.music.playback.SpotifyProfileCache.invalidate() }
            // Friends see the likes and the profile of the new main account.
            runCatching { com.metrolist.music.friends.FriendsHub.mainAccountChanged() }
        }
    }

    /**
     * Whether an account linked for the first time becomes the main one: Spotify always does (it
     * is the preferred main account); Yandex Music or VK only when no account is the main yet.
     * [mainBefore] is the main account before this one was linked.
     */
    fun defaultMakeMain(source: MetadataSource, mainBefore: MetadataSource?): Boolean =
        source == MetadataSource.SPOTIFY || mainBefore == null || mainBefore == source
}
