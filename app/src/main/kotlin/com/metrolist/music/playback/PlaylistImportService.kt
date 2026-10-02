/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Runs [PlaylistImporter] in the foreground, with its progress in a notification, so a long import
 * (a channel with thousands of tracks) goes on when the app is left. Restarted by the system after
 * being killed, it resumes the saved job.
 */
@AndroidEntryPoint
class PlaylistImportService : Service() {
    @Inject
    lateinit var database: MusicDatabase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.import_notification_starting), 0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive != true) {
            if (!PlaylistImporter.isRunning(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            job = scope.launch {
                runCatching {
                    PlaylistImporter.run(this@PlaylistImportService, database) { p ->
                        val manager = getSystemService(NotificationManager::class.java)
                        val text = if (p.finished) {
                            getString(R.string.import_done, p.found, p.total)
                        } else {
                            getString(R.string.import_running, p.found, p.total)
                        }
                        manager.notify(NOTIFICATION_ID, notification("${p.name}: $text", p.done, p.total, p.finished))
                    }
                }.onFailure { Timber.e(it, "PlaylistImportService: import failed") }
                ServiceCompat.stopForeground(this@PlaylistImportService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, done: Int, total: Int, finished: Boolean = false) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.import_playlist))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setAutoCancel(finished)
            .setProgress(if (finished) 0 else total, done, total == 0 && !finished)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "import_playlist"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.import_playlist), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private companion object {
        const val CHANNEL_ID = "playlist_import"
        const val NOTIFICATION_ID = 7331
    }
}
