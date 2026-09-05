package com.aasra.companion.ui.settings

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.ui.theme.AasraTheme
import com.aasra.companion.ui.theme.PaperRaised
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private var modelOpens = 0
    private var previews = 0
    private var backs = 0

    // Use the test APK's DataStore, never the user's settings.
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val prefs = UserPreferencesRepository(testContext)

    @Before
    fun showSettings() {
        runBlocking {
            prefs.setUserName("Asha")
            prefs.setSpeechSpeed(1f)
            prefs.setWakeWordEnabled(true)
        }
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val context = targetContext.createConfigurationContext(
            Configuration(targetContext.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AasraTheme {
                    SettingsScreen(
                        prefs,
                        onBack = { backs++ },
                        onOpenModels = { modelOpens++ },
                        onPreviewVoice = { previews++ },
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Your details").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun wakeWordLabelTogglesTheWholeRow() {
        compose.onNodeWithText("Listening and mode").performClick()
        compose.onNodeWithText("Wake word (Aasra, Help)")
            .performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.waitUntil(5_000) { !runBlocking { prefs.prefs.first().wakeWordEnabled } }
    }

    @Test
    fun namedSpeedChoicesPersistAndExposeSelection() {
        compose.onNodeWithText("Voice and pace").performClick()
        compose.onNodeWithText("Slower").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { prefs.prefs.first().speechSpeed } == 0.75f }
        compose.onNodeWithText("Slower").assertIsSelected()
        compose.onNodeWithText("Faster").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { prefs.prefs.first().speechSpeed } == 1.25f }
        compose.onNodeWithText("Faster").assertIsSelected()
        compose.onNodeWithText("Normal").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { prefs.prefs.first().speechSpeed } == 1f }
    }

    @Test
    fun userNameSavesOnlyOnExplicitSave() {
        compose.onNodeWithText("Your details").performClick()
        compose.onNodeWithText("Your name").performScrollTo().performTextReplacement("  Meera  ")
        assertEquals("Asha", runBlocking { prefs.prefs.first().userName })
        compose.onNodeWithText("Save name").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { prefs.prefs.first().userName } == "Meera" }
    }

    @Test
    fun usageIsCumulativeAndMissingCloudSetupIsFriendly() {
        compose.onNodeWithText("Connections and tools").performClick()
        compose.onNodeWithText("Cloud requests on this device").performScrollTo().assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Listening and mode").performClick()
        compose.onNodeWithText("Cloud is not set up on this device. You can still use offline features.")
            .performScrollTo().assertExists()
        compose.onNodeWithText("local.properties", substring = true).assertDoesNotExist()
        compose.onNodeWithText("this month", substring = true).assertDoesNotExist()
    }

    @Test
    fun navigationAndPreviewInvokeTheirCallbacks() {
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Listening and mode").performClick()
        compose.onNodeWithText("Manage offline models").performScrollTo().performClick()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Voice and pace").performClick()
        compose.onNodeWithText("Hear a sample").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(1, modelOpens)
            assertEquals(1, previews)
        }
        compose.onNodeWithText("Cloud voice: Preeti", substring = true).assertExists()
    }

    @Test
    fun modelsActionUsesSageRatherThanMaterialDefaultPurple() {
        compose.onNodeWithText("Listening and mode").performClick()
        val action = compose.onNodeWithText("Manage offline models").performScrollTo()
        val image = action.captureToImage().toPixelMap()
        assertEquals(PaperRaised, image[10, image.height / 2])
    }
}
