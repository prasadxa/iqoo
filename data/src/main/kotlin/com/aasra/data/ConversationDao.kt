package com.aasra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(turn: ConversationTurn): Long

    /**
     * Newest-first window for the LLM context. Callers reverse it into
     * chronological order before sending to chat completions.
     */
    @Query("SELECT * FROM conversation_log ORDER BY id DESC LIMIT :limit")
    suspend fun lastTurns(limit: Int = 10): List<ConversationTurn>

    @Query("SELECT COUNT(*) FROM conversation_log")
    suspend fun count(): Int

    /** Keep the newest [keep] turns, drop everything older. */
    @Query(
        "DELETE FROM conversation_log WHERE id NOT IN " +
            "(SELECT id FROM conversation_log ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int = 50)

    @Query("DELETE FROM conversation_log")
    suspend fun clear()
}
