package com.aasra.tools

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.room.Room
import com.aasra.data.AasraDatabase
import com.aasra.data.Contact
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DeviceContactLookupTest {
    private lateinit var db: AasraDatabase
    private lateinit var context: Context
    private val provider = TestContactsProvider()
    private var allowed = true

    @Before fun setup() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.READ_CONTACTS && allowed) PackageManager.PERMISSION_GRANTED
                else PackageManager.PERMISSION_DENIED
        }
        db = Room.inMemoryDatabaseBuilder(context, AasraDatabase::class.java).build()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
    }
    @After fun close() { db.close() }

    @Test fun phonebookIsQueriedOnlyAfterPermissionAndNeverImported() = runBlocking {
        allowed = false
        provider.rows = listOf(row(1, "Meera", "+44 (20) 1234-1111", Phone.TYPE_MOBILE))
        val tools = ContactTools(context, db.contacts())
        assertNull(tools.resolve("Meera"))
        assertEquals(0, provider.queries)
        allowed = true
        assertEquals("+442012341111", tools.resolve("Meera")?.phone)
        assertEquals(1, provider.queries)
        assertEquals(0, db.contacts().count())
    }

    @Test fun allNumbersAreReturnedAndDuplicatesAreCollapsed() = runBlocking {
        provider.rows = listOf(row(1, "Meera", "12341111", Phone.TYPE_MOBILE), row(2, "Meera", "12342222", Phone.TYPE_WORK))
        db.contacts().upsert(Contact(name = "Meera", phone = "12341111"))
        val tools = ContactTools(context, db.contacts())
        assertEquals(2, tools.findCandidates("Meera").size)
        assertNull(tools.resolve("Meera"))
        assertEquals("12342222", tools.resolve("Meera work number")?.phone)
        assertEquals("12341111", tools.resolve("Meera mobile")?.phone)
    }

    @Test fun matchingHindiDoesNotLoseVowelsAndDistinctPeopleStayAmbiguous() = runBlocking {
        provider.rows = listOf(row(1, "मीरा शर्मा", "12341111", Phone.TYPE_MOBILE), row(2, "मीरा सिंह", "12342222", Phone.TYPE_HOME))
        val tools = ContactTools(context, db.contacts())
        assertEquals(2, tools.findCandidates("मीरा").size)
        assertEquals("12341111", tools.resolve("मीरा शर्मा")?.phone)
    }

    @Test fun hindiSpeechCanFindEnglishStoredNamesWithoutChangingTheAddressBook() = runBlocking {
        provider.rows = listOf(row(1, "Rahul", "12341111", Phone.TYPE_MOBILE), row(2, "Meera", "12342222", Phone.TYPE_MOBILE))
        val tools = ContactTools(context, db.contacts())
        assertEquals("Rahul", tools.resolve("राहुल")?.name)
        assertEquals("Meera", tools.resolve("मीरा")?.name)
        assertEquals(0, db.contacts().count())
    }

    @Test fun spokenFamilyAliasesWorkForPhoneContacts() = runBlocking {
        provider.rows = listOf(row(1, "Mom", "12341111", Phone.TYPE_MOBILE))
        val tools = ContactTools(context, db.contacts())
        assertEquals("Mom", tools.resolve("मेरी मम्मी")?.name)
        assertEquals("Mom", tools.resolve("my mother")?.name)
    }

    private fun row(id: Long, name: String, number: String, type: Int): Map<String, Any?> = mapOf(
        Phone._ID to id, Phone.DISPLAY_NAME_PRIMARY to name, Phone.NUMBER to number,
        Phone.NORMALIZED_NUMBER to null, Phone.TYPE to type, Phone.LABEL to null,
    )

    private class TestContactsProvider : ContentProvider() {
        var rows = emptyList<Map<String, Any?>>()
        var queries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }.toTypedArray()) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Must not write phone contacts")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Must not delete phone contacts")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Must not update phone contacts")
    }
}
