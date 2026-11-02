/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.friends

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One chat message, sent or received. Relays keep nothing for sure, so this is the history.
 * [id] is the sender's own id inside the encrypted text (the same on both phones, whatever relay
 * or retry it came by). A delete or an edit that arrives before its message is kept as a row too,
 * so the message, when it comes, is ignored ([OnConflictStrategy.IGNORE]) and stays deleted/edited.
 */
@Entity(
    tableName = "message",
    primaryKeys = ["owner", "peer", "id"],
    indices = [Index("owner", "peer", "ts")],
)
data class ChatMessage(
    /** This profile's key (another profile on the same phone has its own history). */
    val owner: String,
    /** The friend this conversation is with. */
    val peer: String,
    val id: String,
    val mine: Boolean,
    /** When it was written (ms, the sender's clock, never later than a minute after it arrived). */
    val ts: Long,
    /** Text with stickers as [Stickers] tokens. */
    val text: String,
    /** A music card ([ChatMusic] as JSON), or null. */
    val music: String? = null,
    val replyTo: String? = null,
    val state: Int = STATE_RECEIVED,
    /** Mine: the friend has read it. Theirs: I have. */
    val read: Boolean = false,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    /** The signed event while it's being sent (the outbox), so a retry sends the very same one. */
    val event: String? = null,
    /** The Nostr event id it went out as (for a deletion request to the relays). */
    val eventId: String? = null,
) {
    companion object {
        const val STATE_SENDING = 0
        const val STATE_SENT = 1
        const val STATE_FAILED = 2
        const val STATE_RECEIVED = 3
    }
}

/**
 * One person's reactions to one message: the whole set they have now (up to [ChatReactions.MAX] keys,
 * oldest first), as their newest word on it says. An empty set stays as a row, so an older word
 * arriving late can't bring a removed reaction back. Kept apart from [ChatMessage]: a reaction may
 * come before its message, and a row in "message" would make that message be ignored when it comes.
 */
@Entity(
    tableName = "reaction",
    primaryKeys = ["owner", "peer", "msgId", "reactor"],
    indices = [Index("owner", "peer")],
)
data class ChatReaction(
    val owner: String,
    val peer: String,
    val msgId: String,
    /** Who reacted: this profile ([owner]) or the friend ([peer]). */
    val reactor: String,
    /** The reaction keys ([ChatReactions]), one per line, oldest first. */
    val keys: String,
    /** The reactor's clock when they set it (ms): only a newer word replaces this one. */
    val ts: Long,
    /** Mine: [ChatMessage.STATE_SENDING], _SENT or _FAILED; theirs: _RECEIVED. */
    val state: Int = ChatMessage.STATE_RECEIVED,
    /** Mine while it's being sent: the signed event, so a retry sends the very same one. */
    val event: String? = null,
) {
    val keyList: List<String> get() = keys.split('\n').filter { it.isNotEmpty() }
}

data class ChatPreview(
    @Embedded val last: ChatMessage,
    val unread: Int,
)

@Dao
interface ChatDao {
    @Query(
        """
        SELECT m.*, (SELECT COUNT(*) FROM message u WHERE u.owner = m.owner AND u.peer = m.peer
                     AND u.mine = 0 AND u.read = 0 AND u.deleted = 0) AS unread
        FROM message m
        WHERE m.owner = :owner AND m.deleted = 0 AND m.ts = (
            SELECT MAX(x.ts) FROM message x WHERE x.owner = m.owner AND x.peer = m.peer AND x.deleted = 0)
        GROUP BY m.peer
        ORDER BY m.ts DESC
        """,
    )
    fun previews(owner: String): Flow<List<ChatPreview>>

    @Query("SELECT * FROM message WHERE owner = :owner AND peer = :peer AND deleted = 0 ORDER BY ts DESC, id DESC")
    fun messages(owner: String, peer: String): Flow<List<ChatMessage>>

    @Query("SELECT COUNT(*) FROM message WHERE owner = :owner AND mine = 0 AND read = 0 AND deleted = 0")
    fun unreadTotal(owner: String): Flow<Int>

    @Query("SELECT * FROM message WHERE owner = :owner AND peer = :peer AND id = :id")
    suspend fun get(owner: String, peer: String, id: String): ChatMessage?

    @Query("SELECT * FROM message WHERE owner = :owner AND mine = 0 AND read = 0 AND deleted = 0 ORDER BY ts")
    suspend fun unreadIncoming(owner: String): List<ChatMessage>

    @Query("SELECT * FROM message WHERE owner = :owner AND mine = 1 AND event IS NOT NULL AND state != 1")
    suspend fun outbox(owner: String): List<ChatMessage>

    /** Mine shown but never signed (the app went between the two), older than [before]. */
    @Query("SELECT * FROM message WHERE owner = :owner AND mine = 1 AND event IS NULL AND state = 0 AND deleted = 0 AND ts < :before")
    suspend fun unsigned(owner: String, before: Long): List<ChatMessage>

    /** -1 when it was already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessage): Long

    @Update
    suspend fun update(message: ChatMessage)

    @Query("UPDATE message SET state = :state, event = CASE WHEN :state = 1 THEN NULL ELSE event END WHERE owner = :owner AND peer = :peer AND id = :id AND state != 1")
    suspend fun setState(owner: String, peer: String, id: String, state: Int)

    @Query("SELECT MAX(ts) FROM message WHERE owner = :owner AND peer = :peer AND mine = 0 AND read = 0 AND deleted = 0")
    suspend fun newestUnread(owner: String, peer: String): Long?

    @Query("UPDATE message SET read = 1 WHERE owner = :owner AND peer = :peer AND mine = 0 AND read = 0")
    suspend fun markIncomingRead(owner: String, peer: String)

    @Query("UPDATE message SET read = 1 WHERE owner = :owner AND peer = :peer AND mine = 1 AND read = 0 AND ts <= :upTo")
    suspend fun markMineRead(owner: String, peer: String, upTo: Long)

    @Query("DELETE FROM message WHERE owner = :owner AND peer = :peer")
    suspend fun clear(owner: String, peer: String)

    // ── Reactions ──

    /** A chat's reactions, on messages still there (a deleted message's go with it). */
    @Query(
        """
        SELECT r.* FROM reaction r
        WHERE r.owner = :owner AND r.peer = :peer AND r.keys != ''
          AND NOT EXISTS (SELECT 1 FROM message m WHERE m.owner = r.owner AND m.peer = r.peer AND m.id = r.msgId AND m.deleted = 1)
        """,
    )
    fun reactions(owner: String, peer: String): Flow<List<ChatReaction>>

    @Query("SELECT * FROM reaction WHERE owner = :owner AND peer = :peer AND msgId = :msgId AND reactor = :reactor")
    suspend fun reaction(owner: String, peer: String, msgId: String, reactor: String): ChatReaction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReaction(reaction: ChatReaction)

    @Query("UPDATE reaction SET state = :state, event = CASE WHEN :state = 1 THEN NULL ELSE event END WHERE owner = :owner AND peer = :peer AND msgId = :msgId AND reactor = :owner AND ts = :ts")
    suspend fun setReactionState(owner: String, peer: String, msgId: String, ts: Long, state: Int)

    /** My reactions not sent yet: signed and waiting, or put but never signed. */
    @Query("SELECT * FROM reaction WHERE owner = :owner AND reactor = :owner AND state IN (0, 2)")
    suspend fun reactionOutbox(owner: String): List<ChatReaction>

    @Query("DELETE FROM reaction WHERE owner = :owner AND peer = :peer AND msgId = :msgId")
    suspend fun dropReactions(owner: String, peer: String, msgId: String)

    @Query("DELETE FROM reaction WHERE owner = :owner AND peer = :peer")
    suspend fun clearReactions(owner: String, peer: String)
}

/** Chats live in their own file, apart from the music library's database and its migrations. */
@Database(
    entities = [ChatMessage::class, ChatReaction::class],
    version = 2,
    exportSchema = true,
    // 2: reactions (a new table; messages untouched).
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class ChatDatabase : RoomDatabase() {
    abstract val dao: ChatDao

    companion object {
        fun open(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, "chat.db").build()
    }
}
