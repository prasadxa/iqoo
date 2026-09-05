package com.aasra.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun lowMemoryInstallSetContainsOnlyTheSmallLanguageModel() {
        val entries = ModelRegistry.defaultSet(lowMemory = true)

        assertTrue(ModelRegistry.QWEN_LOW_MEMORY in entries)
        assertFalse(ModelRegistry.QWEN_2B in entries)
    }

    @Test
    fun normalInstallSetContainsOnlyTheFullLanguageModel() {
        val entries = ModelRegistry.defaultSet(lowMemory = false)

        assertTrue(ModelRegistry.QWEN_2B in entries)
        assertFalse(ModelRegistry.QWEN_LOW_MEMORY in entries)
    }

    @Test
    fun defaultSetContainsEveryRequiredVoicePipelineModel() {
        val entries = ModelRegistry.defaultSet(lowMemory = false)

        assertTrue(ModelRegistry.SILERO_VAD in entries)
        assertTrue(ModelRegistry.SPOKEN_LANGUAGE_ID in entries)
        assertTrue(ModelRegistry.KEYWORD_SPOTTER in entries)
        assertTrue(ModelRegistry.KOKORO in entries)
        assertTrue(ModelRegistry.PIPER_HI in entries)
        assertTrue(ModelRegistry.PIPER_EN in entries)
    }

    @Test
    fun lowMemoryModelIsARealPointEightBArtifact() {
        val entry = ModelRegistry.QWEN_LOW_MEMORY

        assertTrue(entry.url.contains("Qwen3.5-0.8B-GGUF"))
        assertEquals(436_743_552L, entry.sizeBytes)
        assertEquals(64, entry.sha256.length)
    }

    @Test
    fun qwenIntegrityUsesPinnedLfsSha256NotXetStorageHash() {
        // HF tree API lfs.oid and resolver X-Linked-ETag, verified over HTTP.
        assertEquals(
            "635788bdc1b0ba1335e47cca0159e531811c722ffad4e3c7b363c2b55ecc26c8",
            ModelRegistry.QWEN_LOW_MEMORY.sha256,
        )
        assertTrue(ModelRegistry.QWEN_LOW_MEMORY.url.contains("/resolve/fff685b81430bd58e703547bb6014f7b5d482f48/"))
        assertEquals(1_073_069_440L, ModelRegistry.QWEN_2B.sizeBytes)
        assertEquals(
            "8d497863b95e392baf022258f864c34f4a28613df340c500bb647486c52657ae",
            ModelRegistry.QWEN_2B.sha256,
        )
        assertTrue(ModelRegistry.QWEN_2B.url.contains("/resolve/e42cdd7a10a99b70833c43bb1516a696c8075ea8/"))
    }

    @Test
    fun catalogHasUniqueRuntimeTargetsAndVerifiedChecksums() {
        assertEquals(ModelRegistry.ALL.size, ModelRegistry.ALL.map { it.fileName }.distinct().size)
        ModelRegistry.ALL.forEach { entry ->
            assertTrue(entry.url.startsWith("https://"))
            assertTrue(entry.sizeBytes > 0)
            assertTrue(entry.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }
}
