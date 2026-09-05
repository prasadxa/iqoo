package com.aasra.companion.pipeline

import com.aasra.models.ModelRegistry
import com.aasra.sherpa.KeywordSpotter
import com.aasra.sherpa.LanguageId
import com.aasra.sherpa.SherpaTts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRuntimeContractTest {

    @Test
    fun archiveInstallDirectoriesMatchRuntimePaths() {
        assertTrue(SherpaTts.MODEL_PATH.startsWith("models/${ModelRegistry.KOKORO.installDirectory}/"))
        assertTrue(LanguageId.ENCODER_PATH.startsWith("models/${ModelRegistry.SPOKEN_LANGUAGE_ID.installDirectory}/"))
        assertTrue(KeywordSpotter.ENCODER_PATH.startsWith("models/${ModelRegistry.KEYWORD_SPOTTER.installDirectory}/"))
    }

    @Test
    fun kokoroSpeakerIdsMatchMultilingualVoiceTable() {
        assertEquals(3, SherpaTts.VOICE_EN_FEMALE.sid)
        assertEquals(11, SherpaTts.VOICE_EN_MALE.sid)
        assertEquals(31, SherpaTts.VOICE_HI_FEMALE.sid)
        assertEquals(33, SherpaTts.VOICE_HI_MALE.sid)
    }

    @Test
    fun localKeywordPromptDoesNotAdvertiseCloudSearch() {
        assertTrue(KeywordSpotter.ENCODED_KEYWORDS.contains("@aasra"))
        assertTrue(KeywordSpotter.ENCODED_KEYWORDS.contains("@help"))
        assertTrue(KeywordSpotter.ENCODED_KEYWORDS.contains("@madad"))
        assertFalse(KeywordSpotter.ENCODED_KEYWORDS.contains("@web_search"))
    }
}
