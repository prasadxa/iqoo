package com.aasra.companion.ui.main

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import com.aasra.companion.pipeline.FakePipelineOrchestrator
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.ui.theme.AasraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var scope: CoroutineScope
    private var settingsOpens = 0
    private var reminderOpens = 0
    private var healthOpens = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val orchestrator = FakePipelineOrchestrator(scope)
        val viewModel = MainViewModel(orchestrator, UserPreferencesRepository(InstrumentationRegistry.getInstrumentation().context))
        compose.setContent {
            AasraTheme {
                MainScreen(viewModel = viewModel, onOpenSettings = { settingsOpens++ },
                    onOpenReminders = { reminderOpens++ }, onOpenHealth = { healthOpens++ })
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun primaryVoiceActionsAndStateAreAccessible() {
        compose.onNodeWithText("Aasra").assertExists()
        compose.onNodeWithText("Talk").assertExists()
        compose.onNodeWithText("Repeat").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("SOS").assertExists()
        compose.onNodeWithContentDescription("Aasra is ready").assertExists()
    }

    @Test fun careNavigationAndHelpWorkWithoutStartingVoiceOrCallingAnyone() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Reminders").performClick()
        compose.onNodeWithText("Health").performClick()
        compose.onNodeWithContentDescription("What can I ask?").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpens)
            assertEquals(1, reminderOpens)
            assertEquals(1, healthOpens)
        }
    }

    @Test fun sosRequiresConfirmationAndCanBeCancelled() {
        compose.onNodeWithText("SOS").performClick()
        compose.onNodeWithText("Call for help?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Call for help?").assertDoesNotExist()
    }
}
