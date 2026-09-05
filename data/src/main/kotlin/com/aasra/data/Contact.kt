package com.aasra.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One person the user can call or message by voice.
 *
 * `nickname` holds the family word the user actually says ("beta", "bahu",
 * "munna") so [com.aasra.tools.ContactTools] can fuzzy-match it. Onboarding
 * should store the nickname in the same script the user speaks in.
 *
 * `isEmergency` marks SOS recipients; exactly one of them should also have
 * `isPrimary = true` (the contact SOS calls first). Use [ContactDao.saveContact]
 * and [ContactDao.setPrimary] to maintain this when managing contacts. Legacy
 * rows may have no primary or multiple primaries; SOS tolerates both.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val nickname: String? = null,
    val isEmergency: Boolean = false,
    val isPrimary: Boolean = false,
)
