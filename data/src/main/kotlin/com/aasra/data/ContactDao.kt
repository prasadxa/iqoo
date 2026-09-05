package com.aasra.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ContactDao {

    /** Low-level legacy write. New UI add/edit flows should use [saveContact]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: Contact): Long

    @Update
    suspend fun update(contact: Contact)

    @Delete
    suspend fun delete(contact: Contact)

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<Contact>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun byId(id: Long): Contact?

    /** Emergency contacts, primary first. Used by SOS. */
    @Query("SELECT * FROM contacts WHERE isEmergency = 1 ORDER BY isPrimary DESC, name COLLATE NOCASE ASC")
    suspend fun emergency(): List<Contact>

    /** Substring match over name + nickname; final ranking is done in ContactTools. */
    @Query(
        "SELECT * FROM contacts WHERE name LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR nickname LIKE '%' || :q || '%' ESCAPE '\\'",
    )
    suspend fun searchRaw(q: String): List<Contact>

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun count(): Int

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Add (id = 0) or edit an existing contact, returning its stable ID.
     * Throws IllegalArgumentException for invalid input or a deleted edit target.
     * A primary contact is always an emergency contact. All changes are atomic.
     */
    @Transaction
    suspend fun saveContact(contact: Contact): Long {
        require(contact.name.isNotBlank()) { "Contact name is required" }
        val phone = requireNotNull(normalizePhoneNumber(contact.phone)) { "Invalid phone number" }
        require(contact.id == 0L || byId(contact.id) != null) { "Contact no longer exists" }
        val normalized = contact.copy(
            name = contact.name.trim(),
            phone = phone,
            nickname = contact.nickname?.trim()?.takeIf { it.isNotEmpty() },
            isEmergency = contact.isEmergency || contact.isPrimary,
        )
        val id = if (contact.id == 0L) upsert(normalized) else {
            update(normalized)
            contact.id
        }
        if (normalized.isPrimary) setPrimary(id)
        return id
    }

    /**
     * Atomically select one primary, promoting it to an emergency contact.
     * Missing/invalid targets return false without changing any rows. Also
     * repairs duplicate primary flags from legacy writes without deleting data.
     */
    @Transaction
    suspend fun setPrimary(id: Long): Boolean {
        val target = byId(id) ?: return false
        val phone = normalizePhoneNumber(target.phone) ?: return false
        all().filter { it.isPrimary && it.id != id }.forEach {
            update(it.copy(isPrimary = false))
        }
        update(target.copy(phone = phone, isEmergency = true, isPrimary = true))
        return true
    }
}
