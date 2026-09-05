package com.aasra.companion.ui.onboarding

import org.junit.Assert.*
import org.junit.Test

class SetupContactsTest {
    @Test fun emptyContactIsOptionalButHalfCompletedContactIsInvalid() {
        assertTrue(SetupContact("", "").isEmpty)
        assertFalse(SetupContact("Mother", "").isValid)
        assertFalse(SetupContact("", "+91 98765 43210").isValid)
    }

    @Test fun namesAreNeverParsedForPhoneNumbers() {
        val contact = SetupContact("Asha: sister, home", "+91 98765-43210")
        assertTrue(contact.isValid)
        assertEquals("+919876543210", contact.normalizedPhone)
        assertFalse(SetupContact("Asha", "Asha 9876543210").isValid)
        assertFalse(SetupContact("Asha", "*123#").isValid)
    }
}
