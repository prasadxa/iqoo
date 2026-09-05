package com.aasra.companion.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aasra.companion.AasraApp
import com.aasra.companion.pipeline.RealAasraOrchestrator
import com.aasra.companion.ui.main.MainScreen
import com.aasra.companion.ui.main.MainViewModel
import com.aasra.companion.ui.main.MainViewModelFactory
import com.aasra.companion.ui.onboarding.OnboardingWizard
import com.aasra.companion.ui.settings.SettingsScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aasra.companion.R
import com.aasra.companion.prefs.AppLanguage
import com.aasra.companion.ui.models.ModelManagementScreen
import com.aasra.companion.ui.health.HealthScreen
import com.aasra.companion.ui.reminders.RemindersScreen
import com.aasra.companion.ui.tools.ExternalToolsScreen
import com.aasra.companion.service.VoiceService

object Routes {
    const val MAIN = "main"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val HEALTH = "health"
    const val REMINDERS = "reminders"
    const val TOOLS = "tools"
}

/**
 * Three destinations. The start is chosen from DataStore: first launch
 * goes to the wizard, every launch after goes straight to the mic.
 */
@Composable
fun NavGraph() {
    val context = LocalContext.current
    val app = context.applicationContext as AasraApp
    val container = app.container
    val navController = rememberNavController()
    val prefsState by container.prefs.prefs.collectAsState(initial = null)

    if (prefsState == null) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.home_loading))
        }
        return
    }
    // The graph must not change its start destination while completing setup.
    val start = remember { if (prefsState?.onboardingDone == true) Routes.MAIN else Routes.ONBOARDING }
    val configuration = LocalConfiguration.current
    val language = if (prefsState?.language == AppLanguage.HINDI) "hi" else "en"
    val localizedConfig = remember(configuration, language) {
        Configuration(configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    val localizedContext = remember(context, localizedConfig) {
        ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(localizedConfig) }
    }
    fun open(route: String) { navController.navigate(route) { launchSingleTop = true } }
    CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides localizedConfig) {
      NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingWizard(prefs = container.prefs) {
                (container.orchestrator as? RealAasraOrchestrator)?.reloadModels()
                navController.navigate(Routes.MAIN) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            }
        }
        composable(Routes.MAIN) {
            val vm = viewModel<MainViewModel>(
                factory = MainViewModelFactory(container.orchestrator, container.prefs)
            )
            MainScreen(
                viewModel = vm,
                onOpenSettings = { open(Routes.SETTINGS) },
                onOpenModels = { open(Routes.MODELS) },
                onOpenHealth = { open(Routes.HEALTH) },
                onOpenReminders = { open(Routes.REMINDERS) },
            )
        }
        composable(Routes.SETTINGS) {
            val cloud = container.cloud
            val remainingState = if (cloud != null) {
                cloud.usage.remaining.collectAsState(initial = null)
            } else {
                null
            }
            SettingsScreen(
                prefs = container.prefs,
                cloudConfigured = cloud != null,
                rateLimitRemaining = remainingState?.value,
                onBack = { navController.popBackStack() },
                onOpenModels = { open(Routes.MODELS) },
                onOpenTools = { open(Routes.TOOLS) },
                onPreviewVoice = {
                    // Stop cloud capture before local preview so a second audio source
                    // cannot feed its speaker output into the managed microphone.
                    VoiceService.stop(context)
                    container.orchestrator.speak(localizedContext.getString(R.string.home_voice_sample))
                },
            )
        }
        composable(Routes.MODELS) {
            ModelManagementScreen(
                onBack = { navController.popBackStack() },
                onModelsChanged = { (container.orchestrator as? RealAasraOrchestrator)?.reloadModels() },
            )
        }
        composable(Routes.HEALTH) { HealthScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.REMINDERS) { RemindersScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.TOOLS) { ExternalToolsScreen(onBack = { navController.popBackStack() }) }
      }
    }
}
