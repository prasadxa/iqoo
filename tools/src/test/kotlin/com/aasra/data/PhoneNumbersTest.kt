package com.aasra.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun acceptsFormattingWithoutGuessingCountryOrRemovingLeadingZeros() {
        val cases = mapOf(
            "+44 20-7946-0958" to "+442079460958",
            " 020 7946 0958 " to "02079460958",
            "001-202-555-0123" to "0012025550123",
            "+91 98765 43210" to "+919876543210",
            "112" to "112",
            "+123456789012345" to "+123456789012345",
        )
        cases.forEach { (raw, expected) ->
            assertEquals(raw, expected, normalizePhoneNumber(raw))
            assertEquals(expected, normalizePhoneNumber(expected))
        }
    }

    @Test
    fun rejectsNamesMixedTextAndDialControlCharactersRatherThanStrippingThem() {
        listOf(
            "", " ", "Son", "Daughter 1234567890", "1-800-FLOWERS", "+", "12",
            "+1234567890123456", "++123456", "123+456", "-123456", "123456-",
            "tel:123456", "123456;123", "123456,123", "*123#", "123#456",
            "123/456", "123.456", "123\n456", "123\t456", "１２３４５６",
        ).forEach { assertNull(it, normalizePhoneNumber(it)) }
    }
}
