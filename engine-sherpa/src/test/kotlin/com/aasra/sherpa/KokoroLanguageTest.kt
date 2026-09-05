package com.aasra.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroLanguageTest {
    @Test fun hindiLocaleMustNotSelectAnEnglishVoice() {
        assertEquals(SherpaTts.VOICE_HI_FEMALE, SherpaTts.voiceFor("hi-IN", false))
        assertEquals(SherpaTts.VOICE_HI_MALE, SherpaTts.voiceFor("hi-IN", true))
    }

    @Test fun pronunciationLanguageIsExplicitRatherThanInferredFromSpeaker() {
        val hindi = SherpaTts.generationConfig("hi-IN", false, 1f)
        assertEquals("hi", hindi.extra?.get("lang"))
        assertEquals(31, hindi.sid)
        assertEquals("en-us", SherpaTts.generationConfig("en-IN", false, 1f).extra?.get("lang"))
    }
}
