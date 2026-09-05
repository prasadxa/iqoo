package com.aasra.models

import org.junit.Assert.*
import org.junit.Test

class HinglishCatalogTest {
    @Test fun codeSwitchingModelsHaveVerifiedPinnedArtifacts() {
        val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
        files.forEach { file ->
            val entry = ModelRegistry.byFileName("hi-hinglish-swift/$file")
            assertNotNull("Missing code-switching artifact: $file", entry)
            assertTrue(entry!!.url.contains("/2d3ac712ec3a672444297555208dd030060962e3/"))
            assertEquals(64, entry.sha256.length)
        }
    }
}
