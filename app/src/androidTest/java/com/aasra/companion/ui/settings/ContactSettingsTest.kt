package com.aasra.companion.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.aasra.companion.ui.theme.AasraTheme
import com.aasra.data.AasraDatabase
import com.aasra.data.Contact
import com.aasra.data.ContactDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ContactSettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AasraDatabase
    private val dao get() = db.contacts()

    @Before
    fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AasraDatabase::class.java,
        ).build()
    }

    @After fun closeDatabase() = db.close()

    private fun show(contacts: ContactDao = dao) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = target.createConfigurationContext(
            Configuration(target.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AasraTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ContactSettings(contacts)
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Loading contacts").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun addForm(name: String, phone: String) {
        compose.onNodeWithText("Add emergency contact").performScrollTo().performClick()
        compose.onNodeWithText("Contact name").performScrollTo().performTextReplacement(name)
        compose.onNodeWithText("Phone number").performScrollTo().performTextReplacement(phone)
    }

    @Test
    fun invalidPhoneIsNotSavedAndValidPhoneIsNormalized() {
        show()
        addForm("Meera", "Meera")
        compose.onNodeWithText("Save contact").performScrollTo().performClick()
        compose.onNodeWithText("Enter 3 to 15 digits, with an optional + at the start.").assertExists()
        assertEquals(0, runBlocking { dao.count() })
        compose.onNodeWithText("Phone number").performTextReplacement("+91 98765-43210")
        compose.onNodeWithText("Save contact").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { dao.count() } == 1 }
        val saved = runBlocking { dao.emergency().single() }
        assertEquals("+919876543210", saved.phone)
        assertTrue(saved.isPrimary)
    }

    @Test
    fun editKeepsIdAndPrimaryUsesStoredFlagRatherThanPosition() {
        val id = runBlocking { dao.saveContact(Contact(name = "Asha", phone = "12345", isEmergency = true)) }
        show()
        compose.onNodeWithText("Primary (called first)").assertDoesNotExist()
        compose.onNodeWithContentDescription("Make Asha primary").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { dao.byId(id)?.isPrimary } == true }
        compose.onNodeWithText("Primary (called first)").assertExists()
        compose.onNodeWithContentDescription("Edit Asha").performScrollTo().performClick()
        compose.onNodeWithText("Contact name").performScrollTo().performTextReplacement("Asha ji")
        compose.onNodeWithText("Save contact").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { dao.byId(id)?.name } == "Asha ji" }
        assertEquals(1, runBlocking { dao.count() })
        assertTrue(runBlocking { dao.byId(id)!!.isPrimary })
    }

    @Test
    fun deletingLastContactRequiresConfirmationAndExplainsSosLoss() {
        runBlocking { dao.saveContact(Contact(name = "Asha", phone = "12345", isPrimary = true)) }
        show()
        compose.onNodeWithContentDescription("Delete Asha").performScrollTo().performClick()
        compose.onNodeWithText("This is your last emergency contact. Aasra cannot call or message anyone with SOS until you add another.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, runBlocking { dao.count() })
        compose.onNodeWithContentDescription("Delete Asha").performClick()
        compose.onNodeWithText("Delete contact").performClick()
        compose.waitUntil(5_000) { runBlocking { dao.count() } == 0 }
        compose.onNodeWithText("No emergency contacts. Add someone so SOS can call and message them.").assertExists()
    }

    @Test
    fun primarySelectionClearsPreviousPrimary() {
        val first = runBlocking { dao.saveContact(Contact(name = "Asha", phone = "12345", isPrimary = true)) }
        val next = runBlocking { dao.saveContact(Contact(name = "Meera", phone = "67890", isEmergency = true)) }
        show()
        compose.onNodeWithContentDescription("Make Meera primary").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { dao.byId(next)?.isPrimary } == true }
        assertFalse(runBlocking { dao.byId(first)!!.isPrimary })
    }

    @Test
    fun saveIsDisabledWhileWriteIsPending() {
        val releaseSave = CompletableDeferred<Unit>()
        var saves = 0
        val delayedDao = object : ContactDao by dao {
            override suspend fun saveContact(contact: Contact): Long {
                saves++
                releaseSave.await()
                return dao.saveContact(contact)
            }
        }
        show(delayedDao)
        addForm("Meera", "9876543210")
        compose.onNodeWithText("Save contact").performScrollTo().performClick()
        compose.onNodeWithText("Saving").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        releaseSave.complete(Unit)
        compose.waitUntil(5_000) { runBlocking { dao.count() } == 1 }
        assertEquals(1, saves)
    }

    @Test
    fun duplicateNumberIsRejectedWithoutAddingAnotherContact() {
        runBlocking { dao.saveContact(Contact(name = "Asha", phone = "+919876543210", isPrimary = true)) }
        show()
        addForm("Meera", "+91 98765-43210")
        compose.onNodeWithText("Save contact").performScrollTo().performClick()
        compose.onNodeWithText("An emergency contact already uses this number. Edit that contact instead.").assertExists()
        assertEquals(1, runBlocking { dao.count() })
    }

    @Test
    fun failedDeleteShowsErrorInsideConfirmationAndAllowsRetry() {
        runBlocking { dao.saveContact(Contact(name = "Asha", phone = "12345", isPrimary = true)) }
        var failDelete = true
        val failingDao = object : ContactDao by dao {
            override suspend fun deleteById(id: Long) {
                if (failDelete) error("Database unavailable")
                dao.deleteById(id)
            }
        }
        show(failingDao)
        compose.onNodeWithContentDescription("Delete Asha").performScrollTo().performClick()
        compose.onNodeWithText("Delete contact").performClick()
        compose.onNode(hasText("Could not update contacts. Please try again.") and hasAnyAncestor(isDialog())).assertExists()
        assertEquals(1, runBlocking { dao.count() })
        compose.runOnIdle { failDelete = false }
        compose.onNodeWithText("Delete contact").performClick()
        compose.waitUntil(5_000) { runBlocking { dao.count() } == 0 }
    }
}
