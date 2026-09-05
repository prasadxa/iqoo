package com.aasra.companion.ui.onboarding

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.ui.theme.AasraTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class OnboardingWizardTest {
    @get:Rule val compose = createComposeRule()

    private fun showWizard(fontScale: Float = 1f): StateRestorationTester {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply {
                setLocale(Locale.ENGLISH)
                this.fontScale = fontScale
            },
        )
        val prefs = UserPreferencesRepository(instrumentation.context)
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    AasraTheme { OnboardingWizard(prefs) { error("Must not complete during form tests") } }
                }
            }
        }
    }

    private fun next() {
        compose.waitUntil(5_000) {
            !compose.onNodeWithText("Next").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithText("Next").performClick()
    }

    private fun goToContacts() {
        next()
        next()
        compose.onNodeWithText("Your name").performTextReplacement("Meera")
        next()
    }

    @Test fun stepNameAndContactsSurviveSavedStateRestoration() {
        val restoration = showWizard()
        goToContacts()
        compose.onNodeWithText("Person 1: name").performScrollTo().performTextReplacement("Asha: sister")
        compose.onNodeWithText("Person 1: phone number").performScrollTo().performTextReplacement("+91 98765-43210")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("People you trust").assertExists()
        compose.onNodeWithText("Asha: sister").assertExists()
        compose.onNodeWithText("+91 98765-43210").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Meera").assertExists()
    }

    @Test fun missingNameDisablesNextAndMalformedPhoneDoesNotAdvance() {
        showWizard()
        next()
        next()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Your name").performTextReplacement("Meera")
        next()
        compose.onNodeWithText("Person 1: name").performScrollTo().performTextReplacement("Mother")
        compose.onNodeWithText("Person 1: phone number").performScrollTo().performTextReplacement("*123#")
        next()
        compose.onNodeWithText("Enter both a name and a valid phone number.", substring = true).assertExists()
        compose.onNodeWithText("People you trust").assertExists()
    }

    @Test fun skippingContactsRequiresWarningConfirmation() {
        showWizard()
        goToContacts()
        compose.onNodeWithText("Skip contacts for now").performScrollTo().performClick()
        compose.onNodeWithText("Continue without contacts?").assertExists()
        compose.onNodeWithText("SOS needs an emergency contact", substring = true).assertExists()
        compose.onNodeWithText("Add a contact").performClick()
        compose.onNodeWithText("Continue without contacts?").assertDoesNotExist()
        compose.onNodeWithText("People you trust").assertExists()
    }

    @Test fun hindiChoiceUsesHindiScriptAndRemainsSelectedAfterRestore() {
        val restoration = showWizard(fontScale = 1.5f)
        compose.onNodeWithText("हिन्दी").performScrollTo().performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("हिन्दी").assertIsSelected()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Offline voice preference").assertExists()
    }
}
