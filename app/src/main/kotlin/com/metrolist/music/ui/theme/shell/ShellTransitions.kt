/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * How pages change in each experimental theme (null: the standard transitions). A new page is a
 * "push", going back a "pop", the main tabs swap places without depth. With motion reduced every
 * theme just cross-fades.
 *
 *  - Stack: a page comes up from below like a card, the one under it steps back (smaller, dimmer);
 *    back slides it down again.
 *  - Monolith: pages rise a little out of the frame.
 *  - Glass: pages surface (fade in from slightly smaller), like through water.
 *  - Island: pages rise a little, softly.
 *  - Orbit: pages grow out of the centre.
 *  - Paper: a quick fade, nothing slides — a page turned.
 */
object ShellTransitions {
    private const val PAPER_MS = 200
    private const val CALM_MS = 160

    private fun tabs(routes: List<String>, scope: AnimatedContentTransitionScope<NavBackStackEntry>): Boolean {
        val from = scope.initialState.destination.route
        val to = scope.targetState.destination.route
        return from in routes && to in routes
    }

    fun enter(shell: ShellTheme?, scope: AnimatedContentTransitionScope<NavBackStackEntry>, tabRoutes: List<String>): EnterTransition? {
        shell ?: return null
        if (ShellRuntime.calm) return fadeIn(tween(CALM_MS))
        if (tabs(tabRoutes, scope)) return tabEnter(shell)
        return when (shell) {
            ShellTheme.STACK -> slideInVertically(ShellMotion.soft<IntOffset>()) { it } + fadeIn(tween(120), initialAlpha = 0.6f)
            ShellTheme.MONOLITH -> slideInVertically(ShellMotion.soft<IntOffset>()) { it / 16 } + fadeIn(ShellMotion.fadeQuick())
            ShellTheme.GLASS -> scaleIn(ShellMotion.soft(), initialScale = 0.96f) + fadeIn(tween(220))
            ShellTheme.ISLAND -> slideInVertically(ShellMotion.soft<IntOffset>()) { it / 10 } + fadeIn(tween(200))
            ShellTheme.ORBIT -> scaleIn(ShellMotion.soft(), initialScale = 0.92f) + fadeIn(tween(220))
            ShellTheme.PAPER -> fadeIn(tween(PAPER_MS))
            ShellTheme.PULSE, ShellTheme.NONE -> null
        }
    }

    fun exit(shell: ShellTheme?, scope: AnimatedContentTransitionScope<NavBackStackEntry>, tabRoutes: List<String>): ExitTransition? {
        shell ?: return null
        if (ShellRuntime.calm) return fadeOut(tween(CALM_MS))
        if (tabs(tabRoutes, scope)) return tabExit(shell)
        return when (shell) {
            // Steps back under the new card: smaller and dimmer, still there.
            ShellTheme.STACK -> scaleOut(ShellMotion.soft(), targetScale = 0.94f) + fadeOut(tween(320), targetAlpha = 0.55f)
            ShellTheme.MONOLITH -> fadeOut(tween(180))
            ShellTheme.GLASS -> scaleOut(ShellMotion.soft(), targetScale = 1.02f) + fadeOut(tween(180))
            ShellTheme.ISLAND -> fadeOut(tween(180))
            ShellTheme.ORBIT -> scaleOut(ShellMotion.soft(), targetScale = 1.04f) + fadeOut(tween(180))
            ShellTheme.PAPER -> fadeOut(tween(PAPER_MS))
            ShellTheme.PULSE, ShellTheme.NONE -> null
        }
    }

    fun popEnter(shell: ShellTheme?, scope: AnimatedContentTransitionScope<NavBackStackEntry>, tabRoutes: List<String>): EnterTransition? {
        shell ?: return null
        if (ShellRuntime.calm) return fadeIn(tween(CALM_MS))
        if (tabs(tabRoutes, scope)) return tabEnter(shell)
        return when (shell) {
            // The card underneath comes forward again.
            ShellTheme.STACK -> scaleIn(ShellMotion.soft(), initialScale = 0.94f) + fadeIn(tween(260), initialAlpha = 0.55f)
            ShellTheme.MONOLITH -> fadeIn(tween(200))
            ShellTheme.GLASS -> scaleIn(ShellMotion.soft(), initialScale = 1.02f) + fadeIn(tween(220))
            ShellTheme.ISLAND -> fadeIn(tween(200))
            ShellTheme.ORBIT -> scaleIn(ShellMotion.soft(), initialScale = 1.04f) + fadeIn(tween(220))
            ShellTheme.PAPER -> fadeIn(tween(PAPER_MS))
            ShellTheme.PULSE, ShellTheme.NONE -> null
        }
    }

    fun popExit(shell: ShellTheme?, scope: AnimatedContentTransitionScope<NavBackStackEntry>, tabRoutes: List<String>): ExitTransition? {
        shell ?: return null
        if (ShellRuntime.calm) return fadeOut(tween(CALM_MS))
        if (tabs(tabRoutes, scope)) return tabExit(shell)
        return when (shell) {
            ShellTheme.STACK -> slideOutVertically(ShellMotion.soft<IntOffset>()) { it } + fadeOut(tween(300), targetAlpha = 0.8f)
            ShellTheme.MONOLITH -> slideOutVertically(ShellMotion.soft<IntOffset>()) { it / 16 } + fadeOut(ShellMotion.fadeQuick())
            ShellTheme.GLASS -> scaleOut(ShellMotion.soft(), targetScale = 0.96f) + fadeOut(tween(180))
            ShellTheme.ISLAND -> slideOutVertically(ShellMotion.soft<IntOffset>()) { it / 10 } + fadeOut(tween(160))
            ShellTheme.ORBIT -> scaleOut(ShellMotion.soft(), targetScale = 0.92f) + fadeOut(tween(160))
            ShellTheme.PAPER -> fadeOut(tween(PAPER_MS))
            ShellTheme.PULSE, ShellTheme.NONE -> null
        }
    }

    /** The main tabs: no depth, a short swap (Paper: just the fade). */
    private fun tabEnter(shell: ShellTheme): EnterTransition? = when (shell) {
        ShellTheme.PAPER -> fadeIn(tween(PAPER_MS))
        ShellTheme.PULSE, ShellTheme.NONE -> null
        else -> fadeIn(tween(220)) + slideInHorizontally(ShellMotion.soft<IntOffset>()) { it / 25 }
    }

    private fun tabExit(shell: ShellTheme): ExitTransition? = when (shell) {
        ShellTheme.PAPER -> fadeOut(tween(PAPER_MS))
        ShellTheme.PULSE, ShellTheme.NONE -> null
        else -> fadeOut(tween(140)) + slideOutHorizontally(ShellMotion.soft<IntOffset>()) { -it / 25 }
    }
}
