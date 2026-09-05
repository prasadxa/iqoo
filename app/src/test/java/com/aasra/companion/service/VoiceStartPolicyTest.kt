package com.aasra.companion.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStartPolicyTest {
    @Test
    fun startupRequiresSetupPermissionAndForegroundEligibility() {
        for (onboardingDone in listOf(false, true)) {
            for (microphoneGranted in listOf(false, true)) {
                for (appForeground in listOf(false, true)) {
                    for (serviceRunning in listOf(false, true)) {
                        assertEquals(
                            "setup=$onboardingDone mic=$microphoneGranted visible=$appForeground running=$serviceRunning",
                            onboardingDone && microphoneGranted && (appForeground || serviceRunning),
                            voiceStartAllowed(onboardingDone, microphoneGranted, appForeground, serviceRunning),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun permissionGrantAndSettingsReturnCanRetryWithoutChangingMode() {
        val results = listOf(
            voiceStartAllowed(true, false, true, false),
            voiceStartAllowed(true, true, false, false),
            voiceStartAllowed(true, true, true, false),
            voiceStartAllowed(true, false, true, true),
        )
        assertEquals(listOf(false, false, true, false), results)
    }
}
