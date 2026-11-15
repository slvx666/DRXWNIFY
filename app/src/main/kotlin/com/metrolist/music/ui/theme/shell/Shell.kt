/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.R
import com.metrolist.music.ui.theme.LocalThemeAnimations

/**
 * The experimental themes (Settings → Theme and colours → Experimental themes). Unlike the
 * palettes of [com.metrolist.music.ui.theme.AppThemePreset] they change how the app is shaped and
 * how it moves: the navigation bar, the mini player, the player's cover, the page transitions.
 * Never what it does — every screen, action and gesture of the standard look stays.
 *
 * Kept apart from the palettes (their own preference, [NONE] = off): with one on, the palette is
 * the standard Material You one (Paper has its own ink-and-paper one). Docs: docs/EXPERIMENTAL-THEMES.md.
 */
enum class ShellTheme(val nameRes: Int, val descriptionRes: Int) {
    NONE(R.string.shell_none, R.string.shell_none_desc),
    MONOLITH(R.string.shell_monolith, R.string.shell_monolith_desc),
    GLASS(R.string.shell_glass, R.string.shell_glass_desc),
    ISLAND(R.string.shell_island, R.string.shell_island_desc),
    PULSE(R.string.shell_pulse, R.string.shell_pulse_desc),
    STACK(R.string.shell_stack, R.string.shell_stack_desc),
    ORBIT(R.string.shell_orbit, R.string.shell_orbit_desc),
    PAPER(R.string.shell_paper, R.string.shell_paper_desc),
}

/** The experimental theme in use; null with none (everything takes the standard path then). */
val LocalShell = staticCompositionLocalOf<ShellTheme?> { null }

/** The same outside composition (draw lambdas, the audio tap of Pulse). */
object ShellRuntime {
    @Volatile
    var theme: ShellTheme? = null

    /** Motion reduced (the theme's animations switch, the system's setting, the battery saver). */
    @Volatile
    var calm: Boolean = false
}

/** One set of motion for every experimental theme, so they all move as one system. */
object ShellMotion {
    /** Big shapes: panels, the player, cards. */
    fun <T> soft(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 380f)

    /** Buttons, chips, icons. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = 700f)

    /** The "jelly" of Glass and Island: a slight overshoot (~6 %). */
    fun <T> jelly(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 520f)

    /** Calm: what moves when motion is reduced. */
    fun <T> calm(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    fun <T> fadeQuick(): TweenSpec<T> = tween(160, easing = FastOutSlowInEasing)

    fun <T> fadeCalm(): TweenSpec<T> = tween(420, easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f))

    /** Pressed: a little smaller. */
    const val PRESSED_SCALE = 0.96f
}

/**
 * Whether the theme should hold still: its animations switch is off, the system's animations are
 * off ("remove animations" in accessibility) or the battery saver is on.
 */
@Composable
fun shellReduceMotion(): Boolean {
    val themeAnimations = LocalThemeAnimations.current
    val context = LocalContext.current
    val system = remember(context) { systemReducesMotion(context) }
    return !themeAnimations || system
}

fun systemReducesMotion(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val saver = runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)
    return scale == 0f || saver
}

/**
 * Keeps a broken experimental theme from locking the app: a crash in the first minute with a theme
 * on blocks that theme (the next start is in the standard look, and its card says why) until it is
 * picked again.
 */
object ShellGuard {
    private const val PREFS = "shell_guard"
    private const val KEY_BLOCKED = "blocked"
    private const val WINDOW_MS = 60_000L

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var blocked: String? = null

    fun install(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        blocked = prefs.getString(KEY_BLOCKED, null)
        startedAt = android.os.SystemClock.uptimeMillis()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is GuardHandler) return
        Thread.setDefaultUncaughtExceptionHandler(GuardHandler(prefs, previous))
    }

    /** The theme to use for [picked]: [ShellTheme.NONE] when it crashed the app last time. */
    fun effective(picked: ShellTheme): ShellTheme = if (picked != ShellTheme.NONE && picked.name == blocked) ShellTheme.NONE else picked

    /** The theme switched off after a crash, if any. */
    fun blockedTheme(): ShellTheme? = blocked?.let { name -> ShellTheme.entries.firstOrNull { it.name == name } }

    /** Picked again by hand: tried once more. */
    fun unblock(context: Context) {
        blocked = null
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_BLOCKED).apply()
    }

    private class GuardHandler(
        private val prefs: android.content.SharedPreferences,
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val theme = ShellRuntime.theme
                if (theme != null && android.os.SystemClock.uptimeMillis() - startedAt < WINDOW_MS) {
                    // commit, not apply: the process is about to die.
                    prefs.edit().putString(KEY_BLOCKED, theme.name).commit()
                }
            }
            next?.uncaughtException(t, e)
        }
    }
}
