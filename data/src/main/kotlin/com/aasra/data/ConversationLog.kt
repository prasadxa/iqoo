package com.aasra.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One conversation turn. The pipeline keeps the last 10 turns (PLAN 5.1) as
 * the context window for both the local LLM and cloud escalation.
 */
@Entity(tableName = "conversation_log")
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val escalated: Boolean = false,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
