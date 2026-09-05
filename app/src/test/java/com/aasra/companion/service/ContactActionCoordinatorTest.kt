package com.aasra.companion.service

import com.aasra.data.Contact
import com.aasra.tools.ContactCandidate
import com.aasra.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContactActionCoordinatorTest {
    private val mobile = ContactCandidate(Contact(name = "Meera", phone = "12341111"), "mobile")
    private val work = ContactCandidate(Contact(name = "Meera", phone = "12342222"), "work")

    @Test fun selectionThenFreshConfirmationAreRequiredAndCannotReplay() = runBlocking {
        val calls = mutableListOf<Contact>()
        val coordinator = ContactActionCoordinator({ listOf(mobile, work) }, { _, contact, _ -> calls += contact; ToolResult(true, "Calling") })
        val request = ContactActionCoordinator.Request("call", "Meera")
        assertTrue(coordinator.request(request).spoken.contains("Which number"))
        assertTrue(coordinator.respond("yes")!!.pending)
        assertTrue(calls.isEmpty())
        assertTrue(coordinator.respond("second")!!.spoken.contains("2222"))
        assertTrue(coordinator.request(request).pending)
        assertTrue(calls.isEmpty())
        assertTrue(coordinator.respond("yes")!!.ok)
        assertEquals(listOf(work.contact), calls)
        coordinator.request(request)
        assertEquals(1, calls.size)
    }

    @Test fun cancelledExpiredOrUnrelatedResponsesDoNotCall() = runBlocking {
        var time = 0L
        var calls = 0
        val coordinator = ContactActionCoordinator({ listOf(mobile) }, { _, _, _ -> calls++; ToolResult(true, "sent") }, now = { time })
        val request = ContactActionCoordinator.Request("call", "Meera")
        coordinator.request(request)
        coordinator.respond("संजीव")
        assertEquals(0, calls)
        coordinator.respond("cancel")
        assertFalse(coordinator.hasPending)
        coordinator.request(request)
        time = 120_001
        coordinator.respond("yes")
        assertEquals(0, calls)
    }

    @Test fun smsApprovalIsBoundToExactMessage() = runBlocking {
        val sent = mutableListOf<String>()
        val coordinator = ContactActionCoordinator({ listOf(mobile) }, { _, _, message -> sent += message; ToolResult(true, "sent") })
        coordinator.request(ContactActionCoordinator.Request("sms", "Meera", "Hello"))
        coordinator.recordUserResponse("yes")
        coordinator.request(ContactActionCoordinator.Request("sms", "Meera", "Changed text"))
        assertTrue(sent.isEmpty())
        coordinator.respond("yes")
        assertEquals(listOf("Changed text"), sent)
    }

    @Test fun labelAndHindiOrdinalSelectionPreserveAmbiguity() {
        assertEquals(work, ContactActionCoordinator.select("ऑफिस", listOf(mobile, work)))
        assertEquals(work, ContactActionCoordinator.select("दूसरा नंबर", listOf(mobile, work)))
        assertEquals(work, ContactActionCoordinator.select("number ending in 2222", listOf(mobile, work)))
        assertNull(ContactActionCoordinator.select("Meera", listOf(mobile, work)))
        assertNull(ContactActionCoordinator.select("first or second", listOf(mobile, work)))
        assertNull(ContactActionCoordinator.select("mobile", listOf(mobile, mobile.copy(contact = work.contact))))
    }
}
