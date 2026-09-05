package com.aasra.companion.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmationResponseTest {

    @Test
    fun negativeResponseWinsWhenTranscriptAlsoContainsAffirmativeWord() {
        assertEquals(ConfirmationAnswer.NO, classifyConfirmation("okay, no, don't send it"))
    }

    @Test
    fun clearAffirmativeResponseIsAccepted() {
        assertEquals(ConfirmationAnswer.YES, classifyConfirmation("haan, kar do"))
        assertEquals(ConfirmationAnswer.YES, classifyConfirmation("हाँ जी"))
    }

    @Test
    fun devanagariNegativeResponseIsRejected() {
        assertEquals(ConfirmationAnswer.NO, classifyConfirmation("नहीं, मत करो"))
    }

    @Test
    fun unrelatedResponseIsNotTreatedAsConfirmation() {
        assertEquals(ConfirmationAnswer.UNKNOWN, classifyConfirmation("what time is it"))
        assertEquals(ConfirmationAnswer.UNKNOWN, classifyConfirmation("संजीव"))
        assertEquals(ConfirmationAnswer.UNKNOWN, classifyConfirmation("okay later"))
        assertEquals(ConfirmationAnswer.UNKNOWN, classifyConfirmation("yes but call Sita instead"))
    }
}
