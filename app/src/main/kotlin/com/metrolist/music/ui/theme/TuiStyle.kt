package com.metrolist.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The app-wide theme outside composition (shape helpers are also called from draw lambdas and
 * `remember` blocks). Set by MainActivity for the app's own theme — previews drawn in another
 * theme don't touch it.
 */
object ThemeRuntime {
    @Volatile
    var preset: AppThemePreset = AppThemePreset.STANDARD

    /** The Linux theme draws everything as a terminal UI: square corners, no pills or circles. */
    val tui: Boolean get() = preset == AppThemePreset.LINUX
}

private val TuiShape = RoundedCornerShape(2.dp)

/** This shape, or a square one in the Linux theme. */
fun Shape.tui(): Shape = if (ThemeRuntime.tui) TuiShape else this

/** Buttons are pills in Material; square in the Linux theme. */
@Composable
fun tuiButtonShape(): Shape = if (ThemeRuntime.tui) TuiShape else ButtonDefaults.shape
