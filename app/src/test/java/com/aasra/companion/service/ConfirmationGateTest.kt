package com.aasra.companion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationGateTest {

    @Test
    fun repeatedFunctionCallWithoutUserConfirmationIsRejected() {
        val gate = ConfirmationGate { 1_000L }

        assertFalse(gate.confirmed("call:family"))
        assertFalse(gate.confirmed("call:family"))
    }

    @Test
    fun functionCallAfterClearUserConfirmationIsAcceptedOnce() {
        var now = 1_000L
        val gate = ConfirmationGate { now }

        assertFalse(gate.confirmed("call:family"))
        now++
        gate.recordUserResponse("yes, call them")

        assertTrue(gate.confirmed("call:family"))
        assertFalse(gate.confirmed("call:family"))
    }

    @Test
    fun negativeUserResponseNeverConfirmsTheAction() {
        val gate = ConfirmationGate { 1_000L }

        assertFalse(gate.confirmed("sms:family|hello"))
        gate.recordUserResponse("okay, no, don't send it")

        assertFalse(gate.confirmed("sms:family|hello"))
    }
}
