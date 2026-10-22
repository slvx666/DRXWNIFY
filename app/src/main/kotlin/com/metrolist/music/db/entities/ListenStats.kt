package com.metrolist.music.db.entities

/** How many tracks were listened to in a period and for how long (ms). */
data class ListenStats(
    val plays: Int,
    val playTime: Long,
)
