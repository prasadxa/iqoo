package com.aasra.companion.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aasra.companion.pipeline.FakePipelineOrchestrator
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.ui.health.HealthScreen
import com.aasra.companion.ui.main.MainScreen
import com.aasra.companion.ui.main.MainViewModel
import com.aasra.companion.ui.models.ModelManagementScreen
import com.aasra.companion.ui.onboarding.OnboardingWizard
import com.aasra.companion.ui.reminders.RemindersScreen
import com.aasra.companion.ui.settings.SettingsScreen
import com.aasra.companion.ui.theme.AasraTheme
import com.aasra.companion.ui.tools.ExternalToolsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Read-only screen checks. Never grants permissions, downloads, calls or schedules. */
class PhoneScreensTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val prefs = UserPreferencesRepository(instrumentation.context)

    private fun show(content: @Composable () -> Unit) {
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        compose.setContent {
            val resultOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides resultOwner) {
                AasraTheme { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After fun close() { scope.cancel() }

    @Test fun homeActionsAreVisibleWithoutScrolling() {
        val vm = MainViewModel(FakePipelineOrchestrator(scope), prefs)
        show { MainScreen(vm, {}) }
        listOf("Talk", "Repeat", "SOS", "Reminders", "Health").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        capture("home")
    }

    @Test fun setupHasVisibleNext() {
        show { OnboardingWizard(prefs) {} }
        compose.onNodeWithText("Next").assertIsDisplayed()
        capture("setup")
    }

    @Test fun settingsCategoriesAndBackAreVisible() {
        show { SettingsScreen(prefs) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Connections and tools").assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed()
        capture("settings")
    }

    @Test fun downloadsDoNotExposeModelNames() {
        show { ModelManagementScreen({}, {}) }
        compose.onNodeWithText("Qwen", substring = true).assertDoesNotExist()
        compose.onNodeWithText("gguf", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Back").assertIsDisplayed()
        capture("downloads")
    }

    @Test fun healthScreenOpensWithoutGrantingPermission() {
        show { HealthScreen({}) }
        compose.onNodeWithText("Back").assertIsDisplayed()
        capture("health")
    }

    @Test fun remindersKeepAddActionVisible() {
        show { RemindersScreen({}) }
        compose.waitUntil(5_000) {
            !compose.onNodeWithText("Add a reminder").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithText("Add a reminder").assertIsDisplayed()
        capture("reminders")
    }

    @Test fun externalToolsNeverAutoconnect() {
        show { ExternalToolsScreen({}) }
        compose.onNodeWithText("Back").assertIsDisplayed()
        capture("tools")
    }
}
