package com.aasra.companion.ui.models

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.aasra.companion.ui.theme.AasraTheme
import com.aasra.models.ModelRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelDownloadRowTest {
    @get:Rule val compose = createComposeRule()

    private fun showRow(row: ModelRowUi, locale: Locale = Locale.ENGLISH, onRetry: () -> Unit = {}) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = target.createConfigurationContext(
            Configuration(target.resources.configuration).apply { setLocale(locale) },
        )
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AasraTheme { ModelDownloadRow(row, true, onRetry) }
            }
        }
    }

    @Test fun completeTransferShowsVerifyingNotReady() {
        val entry = ModelRegistry.QWEN_LOW_MEMORY
        showRow(ModelRowUi(entry, ModelPhase.VERIFYING, entry.sizeBytes))
        compose.onNodeWithText("Downloaded; verifying").assertExists()
        compose.onNodeWithText("Verified and installed").assertDoesNotExist()
    }

    @Test fun failureRetainsTechnicalReasonAndRetryWorks() {
        var retries = 0
        showRow(ModelRowUi(ModelRegistry.QWEN_LOW_MEMORY, ModelPhase.FAILED, error = "Size/encoding mismatch")) { retries++ }
        compose.onNodeWithText("Technical details: Size/encoding mismatch").assertExists()
        compose.onNodeWithText("Retry this file").performClick()
        assertEquals(1, retries)
    }

    @Test fun checkingAndModelNameAreLocalized() {
        showRow(ModelRowUi(ModelRegistry.QWEN_LOW_MEMORY), Locale.forLanguageTag("hi"))
        compose.onNodeWithText("फ़ाइलों की जाँच हो रही है").assertExists()
        compose.onNodeWithText("ऑफ़लाइन सहायक: Qwen 0.8B").assertExists()
    }
}
