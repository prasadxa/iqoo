package com.aasra.tools

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.room.Room
import com.aasra.data.AasraDatabase
import com.aasra.data.CaregiverStatsStore
import com.aasra.data.Contact
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSmsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33], manifest = Config.NONE, shadows = [ContactSafetyTest.TestSmsManager::class])
@Suppress("DEPRECATION")
class ContactSafetyTest {
    private lateinit var db: AasraDatabase
    private lateinit var context: Context
    private val calls = mutableListOf<Intent>()
    private var permissionRequests = 0
    private var callPermissionGranted = true
    private val contact = Contact(name = "Daughter", phone = "+44 20-7946-0958")

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { calls.add(intent) }
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (callPermissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
        ShadowSmsManager.reset()
        smsShadow().lastDestination = null
        db = Room.inMemoryDatabaseBuilder(context, AasraDatabase::class.java).build()
    }

    @After
    fun tearDown() { if (::db.isInitialized) db.close() }

    private fun callTools() = ContactTools(context, db.contacts()) { permissionRequests++ }

    private fun smsManager(): SmsManager = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(SmsManager::class.java)
    } else {
        SmsManager.getDefault()
    }

    private fun smsShadow(): TestSmsManager = Shadow.extract(smsManager())

    @Test
    fun malformedCallNeverRequestsPermissionOrOpensPhone() {
        callPermissionGranted = false
        val result = callTools().placeCall(contact.copy(phone = "Daughter"))
        assertFalse(result.ok)
        assertEquals("invalid-phone", result.data)
        assertTrue(calls.isEmpty())
        assertEquals(0, permissionRequests)
    }

    @Test
    fun callUsesNormalizedDestination() {
        assertTrue(callTools().placeCall(contact).ok)
        assertEquals("tel:+442079460958", calls.single().data.toString())
        assertEquals(Intent.ACTION_CALL, calls.single().action)
    }

    @Test
    fun missingCallPermissionStillUsesDialerFallback() {
        callPermissionGranted = false
        assertTrue(callTools().placeCall(contact).ok)
        assertEquals(Intent.ACTION_DIAL, calls.single().action)
        assertEquals("tel:+442079460958", calls.single().data.toString())
        assertEquals(1, permissionRequests)
    }

    @Test
    fun declinedCallConfirmationStillPreventsCall() = runBlocking {
        db.contacts().upsert(contact)
        var asked = false
        val result = callTools().callContact(contact.name) { asked = true; false }
        assertTrue(asked)
        assertEquals("cancelled", result.data)
        assertTrue(calls.isEmpty())
    }

    @Test fun duplicateNamesNeverSilentlySelectOneNumber() = runBlocking {
        db.contacts().upsert(Contact(name = "Meera", phone = "12345678"))
        db.contacts().upsert(Contact(name = "Meera", phone = "87654321"))
        assertNull(callTools().resolve("Meera"))
        assertFalse(callTools().callContact("Meera").ok)
        assertTrue(calls.isEmpty())
    }

    @Test fun missingConfirmationAndSmsPermissionNeverSend() = runBlocking {
        db.contacts().upsert(contact)
        assertEquals("needs-confirmation", callTools().callContact(contact.name).data)
        assertTrue(calls.isEmpty())
        callPermissionGranted = false
        assertEquals("needs-sms-permission", SmsTools(context).sendSms(contact, "Hello", true).data)
        assertNull(smsShadow().lastDestination)
    }

    @Test fun hindiNamesKeepTheirVowelMarks() {
        assertEquals("संजय", ContactTools.normalize("संजय"))
        assertEquals("मीरा", ContactTools.normalize("मीरा"))
    }

    @Test
    fun malformedSmsIsRejectedBeforeConfirmationOrSend() = runBlocking {
        val sms = SmsTools(context)
        for (confirmed in listOf(false, true)) {
            val result = sms.sendSms(contact.copy(phone = "Daughter"), "Help", confirmed)
            assertFalse(result.ok)
            assertEquals("invalid-phone", result.data)
        }
        assertFalse(sms.sendDirect("Daughter", "Help"))
        assertNull(smsShadow().lastDestination)
    }

    @Test
    fun smsStillRequiresConfirmationAndNormalizesAfterConfirmation() = runBlocking {
        val sms = SmsTools(context)
        assertEquals("needs-confirmation", sms.sendSms(contact, "Help", false).data)
        assertNull(smsShadow().lastDestination)
        assertTrue(sms.sendSms(contact, "Help", true).ok)
        assertEquals("+442079460958", smsShadow().lastDestination)
    }

    @Test
    fun sosSkipsMalformedPrimaryAndCallsValidEmergencyContact() = runBlocking {
        db.contacts().upsert(contact.copy(name = "Bad", phone = "Son", isEmergency = true, isPrimary = true))
        db.contacts().upsert(contact.copy(isEmergency = true))
        val stats = CaregiverStatsStore(context)
        val result = SosTools(context, db.contacts(), SmsTools(context), stats, callTools()).sos()
        assertTrue(result.ok)
        assertEquals("tel:+442079460958", calls.single().data.toString())
        assertEquals("+442079460958", smsShadow().lastDestination)
        assertTrue(result.spokenReply.contains("1 contacts"))
        assertNotNull(stats.getLastSosAtMillis())
        assertEquals(2, db.contacts().count())
    }

    @Test
    fun sosWithOnlyMalformedContactsHasNoSideEffects() = runBlocking {
        db.contacts().upsert(contact.copy(phone = "Son", isEmergency = true, isPrimary = true))
        val stats = CaregiverStatsStore(context)
        var locationRequested = false
        val result = SosTools(context, db.contacts(), SmsTools(context), stats, callTools()) {
            locationRequested = true
            "location"
        }.sos()
        assertFalse(result.ok)
        assertEquals("invalid-phone", result.data)
        assertTrue(calls.isEmpty())
        assertFalse(locationRequested)
        assertNull(smsShadow().lastDestination)
        assertNull(stats.getLastSosAtMillis())
    }

    // No SIM service exists in Robolectric; keep the short test messages in one part.
    @Implements(SmsManager::class)
    class TestSmsManager {
        var lastDestination: String? = null

        @Implementation
        protected fun divideMessage(text: String): ArrayList<String> = arrayListOf(text)

        @Implementation
        protected fun sendTextMessage(
            destinationAddress: String?, scAddress: String?, text: String?,
            sentIntent: PendingIntent?, deliveryIntent: PendingIntent?,
        ) {
            lastDestination = destinationAddress
        }

        companion object {
            // Android's legacy singleton can retain a shadow from a previous test class.
            @JvmStatic
            @Implementation(maxSdk = 30)
            fun getDefault(): SmsManager = SmsManager.getSmsManagerForSubscriptionId(1)
        }
    }
}
