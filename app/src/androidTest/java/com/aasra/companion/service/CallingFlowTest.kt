package com.aasra.companion.service

import android.Manifest
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.aasra.data.AasraDatabase
import com.aasra.data.Contact
import com.aasra.tools.ContactCandidate
import com.aasra.tools.ContactTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Captures dialing intents instead of starting a phone app. Never makes a real call. */
class CallingFlowTest {
    @Test fun multipleNumbersNeedSelectionAndFreshConfirmationOnAndroid() = runBlocking {
        val intents = mutableListOf<Intent>()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { intents += intent }
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.CALL_PHONE) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val db = Room.inMemoryDatabaseBuilder(context, AasraDatabase::class.java).build()
        try {
            val contacts = ContactTools(context, db.contacts())
            val coordinator = ContactActionCoordinator(
                lookup = { listOf(
                    ContactCandidate(Contact(name = "Meera", phone = "+4412341111"), "mobile"),
                    ContactCandidate(Contact(name = "Meera", phone = "+4412342222"), "work"),
                ) },
                execute = { _, contact, _ -> contacts.placeCall(contact) },
            )
            val request = ContactActionCoordinator.Request("call", "Meera")
            assertTrue(coordinator.request(request).pending)
            coordinator.recordUserResponse("work")
            assertTrue(coordinator.request(request).spoken.contains("2222"))
            assertTrue(intents.isEmpty())
            coordinator.recordUserResponse("yes")
            assertTrue(coordinator.request(request).ok)
            assertEquals(Intent.ACTION_CALL, intents.single().action)
            assertEquals("tel:+4412342222", intents.single().data.toString())
            coordinator.request(request)
            assertEquals(1, intents.size)
        } finally { db.close() }
    }

    @Test fun hindiNameMatchingUsesTheDevicesTransliterator() {
        assertEquals(4, ContactTools.score(ContactTools.normalize("राहुल"), Contact(name = "Rahul", phone = "12341111")))
        assertEquals(4, ContactTools.score(ContactTools.normalize("मीरा"), Contact(name = "Meera", phone = "12342222")))
    }
}
