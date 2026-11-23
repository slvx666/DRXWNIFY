/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.metrolist.music.friends.ChatHub
import com.metrolist.music.friends.CollabPlaylistManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A wake-up from [Push]: a friend wrote (the chat check runs now, not in half an hour) or a guest
 * sent tracks to a shared playlist (its requests are read and applied while the app is closed).
 * Runs on Firebase's own thread, which may be held for a few seconds while the relays answer.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.onToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"]
        Log.i("DrxwPush", "woken: $type")
        when (type) {
            Push.TYPE_COLLAB -> {
                CollabPlaylistManager.listenForRequests()
                // The requests arrive from the relays on their own: a moment to take and publish them.
                Thread.sleep(COLLAB_WAIT_MS)
            }
            else -> {
                val done = CountDownLatch(1)
                ChatHub.runCheck { done.countDown() }
                done.await(CHAT_WAIT_S, TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        const val COLLAB_WAIT_MS = 12_000L
        const val CHAT_WAIT_S = 18L
    }
}
