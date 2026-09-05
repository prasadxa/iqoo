package com.aasra.companion.ui.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.ui.theme.AasraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class SettingsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun notificationAccessRefreshesAfterEveryResume() {
        var granted = false
        var opens = 0
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AasraTheme {
                    Column { NotificationAccessSettings({ granted }, { opens++ }) }
                }
            }
        }
        compose.onNodeWithText("Allow notification access to read recent alerts aloud.").assertExists()
        compose.onNodeWithText("Manage notification access").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, opens)
            owner.registry.currentState = Lifecycle.State.STARTED
            granted = true
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText("Notification reading is enabled.").assertExists()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            granted = false
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText("Allow notification access to read recent alerts aloud.").assertExists()
    }

    @Test
    fun hindiLargeTextOnNarrowScreenStaysReadableAndControlsWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale("hi")) },
        )
        var opens = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                AasraTheme {
                    Column(Modifier.width(320.dp).fillMaxHeight()) {
                        SettingsScreen(
                            UserPreferencesRepository(instrumentation.context),
                            onOpenModels = { opens++ },
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("आपकी जानकारी").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("सेटिंग्स").assertExists()
        capture("settings-hindi-header.png")
        compose.onNodeWithText("सुनना और मोड").performScrollTo().performClick()
        val models = compose.onNodeWithText("ऑफलाइन मॉडल प्रबंधित करें").performScrollTo()
        capture("settings-hindi-large.png")
        models.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opens) }
        val texts = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        texts.filterNot { it.config.contains(SemanticsProperties.EditableText) }.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            }
            layouts.forEach {
                // Semantics can reconstruct a wider paragraph for wrap-content text. Check glyph extents.
                val clipped = it.multiParagraph.didExceedMaxLines || (0 until it.lineCount).any { line ->
                    it.getLineRight(line) - it.getLineLeft(line) > it.size.width + 1 ||
                        it.getLineBottom(line) > it.size.height + 1
                }
                assertFalse("Clipped text: ${it.layoutInput.text}", clipped)
            }
        }
        compose.onNodeWithText("वापस").performClick()
        compose.onNodeWithText("आवाज़ और गति").performScrollTo().performClick()
        compose.onNodeWithText("धीरे").performScrollTo().performClick()
        compose.onNodeWithText("वापस").performClick()
        compose.onNodeWithText("कनेक्शन और टूल").performScrollTo().performClick()
        compose.onNodeWithText("इस फ़ोन पर क्लाउड अनुरोध").performScrollTo().assertExists()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it) } ?: context.getExternalFilesDir(null)!!
        directory.mkdirs()
        File(directory, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
