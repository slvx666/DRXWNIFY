/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.metrolist.music.playback.MusicService

/**
 * The car-radio widget: a head unit with a green LCD (source, quality, title, time) and chunky
 * buttons, as a small square, a 4x1 strip or the full face with the cover in a cassette window —
 * the layout switches as the widget is resized. Drawn by [MetrolistWidgetManager]; the odd ones
 * live in FreakWidgets.
 */
abstract class MeldPlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        refresh(context)
    }

    private fun refresh(context: Context) {
        if (!MusicService.isRunning) return
        runCatching {
            context.startService(Intent(context, MusicService::class.java).setAction(MusicWidgetReceiver.ACTION_UPDATE_WIDGET))
        }
    }
}

class MeldRadioWidget : MeldPlayerWidget()

/** A plain player like every music app has: cover, title, artist, controls; 4x2 adds progress and like. */
class MeldPlayerClassicWidget : MeldPlayerWidget()
