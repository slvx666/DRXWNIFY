/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Persistent resolution cache of the audio fallback layer: for a catalog track (keyed by its catalog
 * id), which provider tracks were confirmed to be that song. One row per (track, provider), so a
 * failing provider can be replaced by an already-known alternate without searching again. Also holds
 * the catalog metadata needed to re-run resolution after a restart.
 */
@Entity(
    tableName = "audio_fallback_match",
    primaryKeys = ["catalogId", "provider"],
    indices = [Index("catalogId")],
)
data class AudioFallbackMatchEntity(
    val catalogId: String,
    /** [com.metrolist.music.resolver.AudioProviderId] name. */
    val provider: String,
    val providerTrackId: String,
    val matchedTitle: String,
    val matchedArtist: String,
    val confidence: Double,
    /** Catalog metadata of the track (authoritative, never replaced by provider metadata). */
    val title: String,
    val artists: String,
    val album: String?,
    val durationMs: Long,
    val isrc: String?,
    /** True for the provider that won the last resolution. */
    val selected: Boolean,
    val matchedAt: Long = System.currentTimeMillis(),
)
