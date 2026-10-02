package com.metrolist.music.ui.screens.equalizer

import com.metrolist.music.eq.data.SavedEQProfile

/**
 * UI State for EQ Screen
 */
data class EQState(
    val profiles: List<SavedEQProfile> = emptyList(),
    val activeProfileId: String? = null,
    val importStatus: String? = null,
    val error: String? = null,
    /** The 10-band graphic equalizer (see GraphicEq). */
    val graphicGains: FloatArray = FloatArray(GraphicEq.FREQUENCIES.size),
    val graphicPreset: GraphicEq.Preset? = GraphicEq.Preset.FLAT,
)