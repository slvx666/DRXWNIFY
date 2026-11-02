/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.friends

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Insets
import android.os.Build
import android.os.CancellationSignal
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * The chat's keyboard opened and closed at the app's own pace, softer than the phone's (which
 * snaps): the system lends the keyboard's position to the app and the app moves it frame by
 * frame. Where it isn't lent (before Android 11, a keyboard that doesn't allow it, or not in
 * time), [fallback] shows or hides it the usual way.
 */
internal object ImeMotion {
    private const val OPEN_MS = 320L
    private const val CLOSE_MS = 260L
    private const val WAIT_MS = 250L

    private var running: CancellationSignal? = null

    fun show(view: View, fallback: () -> Unit) = start(view, true, fallback, null)

    fun hide(view: View, onDone: () -> Unit, fallback: () -> Unit) = start(view, false, fallback, onDone)

    private fun start(view: View, show: Boolean, fallback: () -> Unit, onDone: (() -> Unit)?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fallback()
            onDone?.invoke()
            return
        }
        // Two frames on: a field focused just now has its connection to the keyboard by then.
        view.postOnAnimation { view.postOnAnimation { control(view, show, fallback, onDone) } }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun control(view: View, show: Boolean, fallback: () -> Unit, onDone: (() -> Unit)?) {
        val insets = view.windowInsetsController
        if (insets == null) {
            fallback()
            onDone?.invoke()
            return
        }
        running?.cancel()
        val signal = CancellationSignal().also { running = it }
        var lent = false
        var fellBack = false
        fun fallBack() {
            if (fellBack || lent) return
            fellBack = true
            fallback()
            onDone?.invoke()
        }
        val listener = object : WindowInsetsAnimationControlListener {
            override fun onReady(controller: WindowInsetsAnimationController, types: Int) {
                if (fellBack) {
                    controller.finish(show)
                    return
                }
                lent = true
                val from = controller.currentInsets.bottom
                val to = if (show) controller.shownStateInsets.bottom else controller.hiddenStateInsets.bottom
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = if (show) OPEN_MS else CLOSE_MS
                    // Out quickly and settling in; away gently, then quicker.
                    interpolator = if (show) PathInterpolator(0.2f, 0f, 0f, 1f) else PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
                    addUpdateListener { a ->
                        if (!controller.isReady) return@addUpdateListener
                        val f = a.animatedValue as Float
                        controller.setInsetsAndAlpha(Insets.of(0, 0, 0, (from + (to - from) * f).roundToInt()), 1f, f)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (controller.isReady) controller.finish(show)
                        }
                    })
                    start()
                }
            }

            override fun onFinished(controller: WindowInsetsAnimationController) {
                if (running === signal) running = null
                if (lent) onDone?.invoke()
            }

            override fun onCancelled(controller: WindowInsetsAnimationController?) {
                if (running === signal) running = null
                if (lent) onDone?.invoke() else fallBack()
            }
        }
        runCatching {
            insets.controlWindowInsetsAnimation(WindowInsets.Type.ime(), -1, LinearInterpolator(), signal, listener)
        }.onFailure { fallBack() }
        // Not lent in time: the usual way.
        view.postDelayed({ if (!lent) fallBack() }, WAIT_MS)
    }
}
