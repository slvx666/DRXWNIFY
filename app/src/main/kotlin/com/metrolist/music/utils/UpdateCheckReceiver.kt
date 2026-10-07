/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.metrolist.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Looks for a new version about once an hour in the background (an inexact alarm: the system
 * batches it with other wake-ups, no battery cost worth mentioning). A new version is announced with
 * one quiet notification at most once a day — never a pop-up, never every hour.
 */
class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                check(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 4141
        private const val NOTIFY_EVERY_MS = 24 * 60 * 60 * 1000L

        /** Called at app start; re-scheduling the same alarm is harmless. */
        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = PendingIntent.getBroadcast(
                context, 0, Intent(context, UpdateCheckReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR,
                intent,
            )
        }

        private suspend fun check(context: Context) {
            AppUpdater.check()
            val available = AppUpdater.state.value as? AppUpdater.State.Available ?: return
            val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("notified_at", 0L) < NOTIFY_EVERY_MS) return
            prefs.edit().putLong("notified_at", now).apply()
            notify(context, available)
        }

        private fun notify(context: Context, available: AppUpdater.State.Available) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.app_update_title), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val url = Updater.UPDATE_PAGE_URL.takeIf { it.isNotBlank() }
                ?.let { "${it.trimEnd('/')}/?v=${AppUpdater.currentVersion}" }
                ?: available.pageUrl
                ?: AppUpdater.RELEASES_URL
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                manager.notify(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.small_icon)
                        .setContentTitle(context.getString(R.string.app_update_offer_title, available.version))
                        .setContentText(context.getString(R.string.app_update_notification_text))
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOnlyAlertOnce(true)
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build(),
                )
            }
        }
    }
}
