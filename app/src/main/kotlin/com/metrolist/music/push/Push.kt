/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.push

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.metrolist.music.BuildConfig
import com.metrolist.music.friends.ChatHub
import com.metrolist.music.friends.CollabPlaylistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Instant wake-ups over Firebase Cloud Messaging. Chats and shared playlists go over nostr, which no
 * server watches: so the sender's app asks the site's push function to wake the other phone, and
 * that phone then fetches what came from the relays itself, as its half-hourly check does. Nothing
 * of a message goes through the push: only "chat" or "collab".
 *
 * Each phone's push address (token) goes to its friends inside the encrypted hello, and to guests
 * of its shared playlists inside their published state. A phone without Google Play services (or a
 * build without the Firebase config) gets no token and keeps the half-hourly check.
 */
object Push {
    private const val TAG = "DrxwPush"
    private const val ENDPOINT = "https://drxwnify.pages.dev/api/push"
    const val TYPE_CHAT = "chat"
    const val TYPE_COLLAB = "collab"

    /** Messages sent in a row wake the other phone once, after the last of them. */
    private const val WAKE_DEBOUNCE_MS = 1_500L

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingWakes = ConcurrentHashMap<String, Job>()

    private val configured: Boolean
        get() = BuildConfig.PUSH_AVAILABLE && BuildConfig.FCM_APP_ID.isNotEmpty()

    /** This phone's push address, once Firebase gave one. */
    val token: String? get() = prefs?.getString("token", null)

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences("push", Context.MODE_PRIVATE)
        if (!configured) return
        runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(
                    app,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FCM_APP_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .build(),
                )
            }
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { onToken(it) }
                .addOnFailureListener { Log.w(TAG, "no push token: ${it.message}") }
        }.onFailure { Log.w(TAG, "push unavailable: ${it.message}") }
    }

    /** A new address from Firebase: friends and guests learn it (the hello again, the shared playlists again). */
    internal fun onToken(token: String) {
        if (token.isBlank() || token == this.token) return
        prefs?.edit()?.putString("token", token)?.apply()
        Log.i(TAG, "push address updated")
        runCatching { ChatHub.pushTokenChanged() }
        runCatching { CollabPlaylistManager.republishOwn() }
    }

    /** Wakes the phone at push address [to] (shortly, once for a burst). Nothing happens without an address. */
    fun wake(to: String?, type: String) {
        if (to.isNullOrBlank()) return
        val key = "$type|$to"
        pendingWakes.remove(key)?.cancel()
        pendingWakes[key] = scope.launch {
            delay(WAKE_DEBOUNCE_MS)
            pendingWakes.remove(key)
            send(to, type)
        }
    }

    private fun send(to: String, type: String) {
        runCatching {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("token", to).put("type", type).toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) Log.i(TAG, "wake $type answered $code")
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "wake $type failed: ${it.message}") }
    }
}
