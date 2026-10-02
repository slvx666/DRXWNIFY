/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.equalizer

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.eq.data.FilterType
import com.metrolist.music.eq.data.ParametricEQBand
import com.metrolist.music.eq.data.SavedEQProfile

/**
 * A 10-band graphic equalizer on top of the app's parametric engine, with presets for listening
 * and for picking out instruments ("Vocals" lifts the 2–3 kHz presence where a voice lives).
 */
object GraphicEq {
    const val PROFILE_ID = "meld_graphic"

    /** Band centres, Hz: the usual octave spacing. */
    val FREQUENCIES = doubleArrayOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val LABELS = listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val MAX_DB = 12f

    enum class Preset(@StringRes val title: Int, val gains: FloatArray) {
        FLAT(R.string.eq_flat, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        BASS(R.string.eq_bass, floatArrayOf(7f, 6f, 4f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
        VOCALS(R.string.eq_vocals, floatArrayOf(-2f, -2f, -1f, 0f, 1f, 2.5f, 4.5f, 3f, 1f, 0f)),
        GUITAR(R.string.eq_guitar, floatArrayOf(-1f, 0f, 1f, -2f, 0f, 1.5f, 3.5f, 4f, 2f, 0f)),
        BASS_GUITAR(R.string.eq_bass_guitar, floatArrayOf(3f, 5f, 4f, 2f, 0f, 1.5f, 2.5f, 0f, -1f, -2f)),
        DRUMS(R.string.eq_drums, floatArrayOf(4f, 5f, 2f, -2f, -2f, 0f, 2f, 4f, 3f, 2f)),
        PIANO(R.string.eq_piano, floatArrayOf(0f, 1f, 2f, 2f, 1f, 0f, 1.5f, 2.5f, 2f, 1f)),
        STRINGS(R.string.eq_strings, floatArrayOf(-1f, 0f, 1f, 2f, 2f, 1f, 1f, 2.5f, 3f, 3f)),
        SPEECH(R.string.eq_speech, floatArrayOf(-6f, -4f, -1f, 1f, 2.5f, 3.5f, 3.5f, 2f, 0f, -2f)),
        METAL(R.string.eq_metal, floatArrayOf(4f, 4f, 2f, -1f, -3f, -2f, 1f, 3.5f, 4f, 3f)),
        ELECTRONIC(R.string.eq_electronic, floatArrayOf(6f, 5f, 2f, 0f, -1f, 0f, 1f, 2.5f, 4f, 5f)),
        AIR(R.string.eq_air, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 6f)),
        NIGHT(R.string.eq_night, floatArrayOf(-4f, -3f, -1f, 0f, 1f, 1.5f, 1f, -1f, -3f, -4f)),
        PHONE_SPEAKER(R.string.eq_phone_speaker, floatArrayOf(-12f, -10f, -4f, 1f, 2f, 3f, 3f, 2f, 1f, 0f)),
        OLD_RADIO(R.string.eq_old_radio, floatArrayOf(-12f, -12f, -8f, -2f, 3f, 5f, 3f, -6f, -12f, -12f)),
        UNDERWATER(R.string.eq_underwater, floatArrayOf(6f, 6f, 4f, 2f, -2f, -6f, -10f, -12f, -12f, -12f)),
    }

    /** The profile the engine plays: peaking filters, and enough negative preamp to never clip. */
    fun profile(gains: FloatArray, name: String): SavedEQProfile = SavedEQProfile(
        id = PROFILE_ID,
        name = name,
        deviceModel = "",
        bands = FREQUENCIES.mapIndexed { i, f ->
            ParametricEQBand(frequency = f, gain = gains[i].toDouble(), q = 1.41, filterType = FilterType.PK)
        },
        preamp = -(gains.maxOrNull()?.coerceAtLeast(0f) ?: 0f).toDouble(),
        isCustom = false,
    )

    fun gainsOf(profile: SavedEQProfile?): FloatArray? =
        profile?.takeIf { it.id == PROFILE_ID && it.bands.size == FREQUENCIES.size }
            ?.bands?.map { it.gain.toFloat() }?.toFloatArray()
}

@Composable
fun GraphicEqSection(
    gains: FloatArray,
    selectedPreset: GraphicEq.Preset?,
    onPreset: (GraphicEq.Preset) -> Unit,
    onBand: (index: Int, gain: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.eq_graphic), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            GraphicEq.Preset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == selectedPreset,
                    onClick = { onPreset(preset) },
                    label = { Text(stringResource(preset.title)) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        ) {
            gains.forEachIndexed { index, gain ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        text = (if (gain > 0) "+" else "") + "%.0f".format(gain),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A vertical slider: a horizontal one turned a quarter.
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).width(32.dp)) {
                        Slider(
                            value = gain,
                            onValueChange = { onBand(index, (Math.round(it * 2) / 2f)) },
                            valueRange = -GraphicEq.MAX_DB..GraphicEq.MAX_DB,
                            colors = SliderDefaults.colors(),
                            modifier = Modifier
                                .requiredWidth(150.dp)
                                .graphicsLayer { rotationZ = -90f },
                        )
                    }
                    Text(GraphicEq.LABELS[index], style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { onPreset(GraphicEq.Preset.FLAT) }) { Text(stringResource(R.string.eq_reset)) }
    }
}
