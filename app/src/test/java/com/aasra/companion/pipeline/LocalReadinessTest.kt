package com.aasra.companion.pipeline

import org.junit.Assert.*
import org.junit.Test

class LocalReadinessTest {
    @Test fun captureDefaultsClosedAndStopSurvivesForegroundUpdates() {
        val gate = CaptureGate()
        assertFalse(gate.allowed(RunMode.HYBRID))
        gate.setEnabled(true)
        assertTrue(gate.allowed(RunMode.HYBRID))
        assertFalse(gate.allowed(RunMode.CLOUD))
        gate.stop()
        gate.setEnabled(true) // repeated service/preference event is not a resume
        assertFalse(gate.allowed(RunMode.OFFLINE))
        gate.resume()
        assertTrue(gate.allowed(RunMode.OFFLINE))
        gate.setEnabled(false)
        gate.resume() // Talk cannot bypass service admission
        assertFalse(gate.allowed(RunMode.HYBRID))
        gate.setEnabled(true)
        assertTrue(gate.allowed(RunMode.HYBRID))
    }

    @Test fun installedFailedModelsDoNotMakeLocalVoiceReady() {
        val readiness = LocalReadiness(mapOf(
            LocalCapability.STT to CapabilityReadiness(ReadinessStatus.READY, true, "English ready"),
            LocalCapability.LLM to CapabilityReadiness(ReadinessStatus.FAILED, true, "Reload runtime"),
            LocalCapability.TTS to CapabilityReadiness(ReadinessStatus.MISSING, false, "Install voice"),
        ))
        assertEquals(ReadinessStatus.FAILED, readiness.status)
        assertFalse(readiness.canAssist)
        assertTrue(LocalCapability.LLM in readiness.installedCapabilities)
        assertTrue(LocalCapability.TTS in readiness.missingCapabilities)
    }

    @Test fun energyVadAndMissingWakeWordDoNotBlockForegroundVoice() {
        val readiness = LocalReadiness(mapOf(
            LocalCapability.STT to CapabilityReadiness(ReadinessStatus.READY, true, "Ready"),
            LocalCapability.LLM to CapabilityReadiness(ReadinessStatus.READY, true, "Ready"),
            LocalCapability.TTS to CapabilityReadiness(ReadinessStatus.READY, true, "Ready"),
            LocalCapability.VAD to CapabilityReadiness(ReadinessStatus.READY, false, "Energy fallback"),
            LocalCapability.WAKE_WORD to CapabilityReadiness(ReadinessStatus.MISSING, false, "Install wake words"),
        ))
        assertTrue(readiness.canAssist)
        assertEquals(ReadinessStatus.READY, readiness.status)
    }

    @Test fun offlineAlwaysDisallowsCloudEvenWithAClientAndNetwork() {
        assertFalse(cloudAllowed(RunMode.OFFLINE, true, true))
        assertFalse(cloudAllowed(RunMode.HYBRID, false, true))
        assertFalse(cloudAllowed(RunMode.HYBRID, true, false))
        assertTrue(cloudAllowed(RunMode.HYBRID, true, true))
    }
}
