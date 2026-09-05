package com.aasra.data

import androidx.room.Room
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ContactDaoTest {
    private lateinit var db: AasraDatabase
    private lateinit var dao: ContactDao
    private val contact = Contact(name = "Daughter", phone = "+44 20-7946-0958", isEmergency = true)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AasraDatabase::class.java).build()
        dao = db.contacts()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun saveAddsAndEditsWithoutChangingIdentity() = runBlocking {
        val id = dao.saveContact(contact.copy(name = " Daughter ", nickname = " Kid ", isPrimary = true))
        assertTrue(id > 0)
        assertEquals(contact.copy(id = id, phone = "+442079460958", nickname = "Kid", isPrimary = true), dao.byId(id))
        assertEquals(id, dao.saveContact(dao.byId(id)!!.copy(name = "Child", phone = "020-7946 0958")))
        assertEquals(1, dao.count())
        assertEquals("02079460958", dao.byId(id)!!.phone)
        assertEquals("Child", dao.byId(id)!!.name)
    }

    @Test
    fun invalidSaveDoesNotInsertOrOverwriteExistingContact() = runBlocking {
        val id = dao.saveContact(contact.copy(isPrimary = true))
        val before = dao.all()
        for (bad in listOf(contact.copy(phone = "Son"), contact.copy(name = " "), contact.copy(id = id, phone = "Son"))) {
            try {
                dao.saveContact(bad)
                fail("Invalid contact should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, dao.all())
        }
    }

    @Test
    fun malformedLegacyRowCanBeRepairedWithoutDeletingOrDuplicatingIt() = runBlocking {
        val id = dao.upsert(contact.copy(phone = "Daughter", isPrimary = true))
        assertEquals(id, dao.saveContact(dao.byId(id)!!.copy(phone = contact.phone)))
        assertEquals(1, dao.count())
        assertEquals("+442079460958", dao.byId(id)!!.phone)
        assertTrue(dao.byId(id)!!.isPrimary)
    }

    @Test
    fun editingDeletedContactDoesNotResurrectIt() = runBlocking {
        val id = dao.saveContact(contact)
        dao.deleteById(id)
        dao.deleteById(id)
        try {
            dao.saveContact(contact.copy(id = id))
            fail("Missing contact should be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, dao.count())
    }

    @Test
    fun primarySelectionRepairsLegacyDuplicateFlagsAndPromotesTargetToEmergency() = runBlocking {
        dao.upsert(contact.copy(name = "Old one", isPrimary = true))
        dao.upsert(contact.copy(name = "Old two", isEmergency = false, isPrimary = true))
        val target = dao.upsert(contact.copy(isEmergency = false))
        assertTrue(dao.setPrimary(target))
        assertEquals(listOf(target), dao.all().filter { it.isPrimary }.map { it.id })
        assertTrue(dao.byId(target)!!.isEmergency)
        assertEquals("+442079460958", dao.byId(target)!!.phone)
    }

    @Test
    fun missingOrMalformedPrimaryTargetDoesNotClearExistingPrimary() = runBlocking {
        dao.saveContact(contact.copy(isPrimary = true))
        val invalid = dao.upsert(contact.copy(name = "Legacy", phone = "Son"))
        val before = dao.all()
        assertFalse(dao.setPrimary(Long.MAX_VALUE))
        assertFalse(dao.setPrimary(invalid))
        assertEquals(before, dao.all())
    }

    @Test
    fun savingNewPrimaryClearsOldPrimaryAndDemotionClearsItsFlag() = runBlocking {
        val old = dao.saveContact(contact.copy(isPrimary = true))
        val next = dao.saveContact(contact.copy(name = "Next", isEmergency = false, isPrimary = true))
        assertFalse(dao.byId(old)!!.isPrimary)
        assertTrue(dao.byId(next)!!.isEmergency)
        dao.saveContact(dao.byId(next)!!.copy(isEmergency = false, isPrimary = false))
        assertFalse(dao.byId(next)!!.isPrimary)
        assertFalse(dao.byId(next)!!.isEmergency)
    }

    @Test
    fun primaryChangeRollsBackIfTargetUpdateFails() = runBlocking {
        dao.saveContact(contact.copy(isPrimary = true))
        val next = dao.saveContact(contact.copy(name = "Next"))
        val before = dao.all()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_primary BEFORE UPDATE ON contacts " +
                "WHEN NEW.id = $next AND NEW.isPrimary = 1 " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        try {
            dao.setPrimary(next)
            fail("Trigger should abort the update")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, dao.all())
    }

    @Test
    fun concurrentPrimaryChangesLeaveExactlyOnePrimary() = runBlocking {
        val ids = (1..4).map { dao.saveContact(contact.copy(name = "Contact $it")) }
        ids.map { id -> async { assertTrue(dao.setPrimary(id)) } }.awaitAll()
        assertEquals(1, dao.all().count { it.isPrimary })
    }

    @Test
    fun deleteOnlyRemovesRequestedContactIncludingMalformedLegacyRows() = runBlocking {
        val keep = dao.saveContact(contact.copy(isPrimary = true))
        val remove = dao.upsert(contact.copy(phone = "Son"))
        dao.deleteById(remove)
        assertEquals(listOf(keep), dao.all().map { it.id })
        assertTrue(dao.byId(keep)!!.isPrimary)
    }
}
