package com.aasra.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aasra.companion.R
import com.aasra.companion.ui.components.PageHeader
import com.aasra.companion.ui.components.PhoneAccessPanel
import com.aasra.companion.pipeline.RunMode
import com.aasra.companion.prefs.AppLanguage
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.prefs.VoiceChoice
import com.aasra.data.AasraDatabase
import com.aasra.tools.NotificationAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Settings use the localized context supplied by navigation. Cloud usage is cumulative. */
@Composable
fun SettingsScreen(
    prefs: UserPreferencesRepository,
    cloudConfigured: Boolean = false,
    rateLimitRemaining: Int? = null,
    onBack: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onOpenTools: () -> Unit = {},
) {
    val state by prefs.prefs.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val contacts = remember(context.applicationContext) { AasraDatabase.get(context).contacts() }
    var savingPreference by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("overview") }
    val scrollState = rememberScrollState()
    LaunchedEffect(section) { scrollState.scrollTo(0) }
    BackHandler(enabled = section != "overview") { section = "overview" }

    fun save(action: suspend () -> Unit) {
        if (savingPreference) return
        savingPreference = true
        saveError = false
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                saveError = true
            } finally {
                savingPreference = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeader(stringResource(when (section) {
                "personal" -> R.string.settings_personal
                "voice" -> R.string.settings_voice_group
                "listening" -> R.string.settings_listening_group
                "contacts" -> R.string.settings_contacts_title
                "advanced" -> R.string.settings_advanced_group
                "language" -> R.string.settings_language
                else -> R.string.settings_title
            }), stringResource(R.string.settings_back), { if (section == "overview") onBack() else section = "overview" })
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val current = state
            if (current == null) {
                Text(stringResource(R.string.settings_loading))
                return@Column
            }
            if (saveError) {
                Text(
                    stringResource(R.string.settings_save_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (section == "overview") {
                Text(stringResource(R.string.settings_intro), style = MaterialTheme.typography.bodyMedium)
                SettingsSectionLink(stringResource(R.string.settings_personal), Icons.Default.Person) { section = "personal" }
                SettingsSectionLink(stringResource(R.string.settings_voice_group), Icons.Default.RecordVoiceOver) { section = "voice" }
                SettingsSectionLink(stringResource(R.string.settings_language), Icons.Default.Language) { section = "language" }
                SettingsSectionLink(stringResource(R.string.settings_listening_group), Icons.Default.Hearing) { section = "listening" }
                SettingsSectionLink(stringResource(R.string.settings_contacts_title), Icons.Default.Call) { section = "contacts" }
                SettingsSectionLink(stringResource(R.string.settings_advanced_group), Icons.Default.Tune) { section = "advanced" }
                return@Column
            }

          if (section == "personal") {
            var name by rememberSaveable(current.userName) { mutableStateOf(current.userName) }
            var nameSubmitted by rememberSaveable { mutableStateOf(false) }
            var nameSaved by rememberSaveable { mutableStateOf(false) }
            SectionTitle(stringResource(R.string.settings_name_title))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameSaved = false },
                label = { Text(stringResource(R.string.settings_user_name)) },
                isError = nameSubmitted && name.isBlank(),
                supportingText = if (nameSubmitted && name.isBlank()) {
                    { Text(stringResource(R.string.settings_name_required)) }
                } else null,
                enabled = !savingPreference,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    nameSubmitted = true
                    if (name.isNotBlank()) {
                        val enteredName = name.trim()
                        save {
                            prefs.setUserName(enteredName)
                            nameSaved = true
                            focus.clearFocus()
                        }
                    }
                },
                enabled = !savingPreference && name.trim() != current.userName,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text(stringResource(R.string.settings_save_name), textAlign = TextAlign.Center) }
            if (nameSaved) {
                Text(stringResource(R.string.settings_name_saved), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
          }

          if (section == "listening") {
            SectionTitle(stringResource(R.string.settings_mode_title))
            Column(Modifier.selectableGroup()) {
                ModeRow(
                    stringResource(R.string.settings_offline),
                    stringResource(R.string.settings_offline_detail),
                    current.runMode == RunMode.OFFLINE,
                    enabled = !savingPreference,
                ) { save { prefs.setRunMode(RunMode.OFFLINE) } }
                ModeRow(
                    stringResource(R.string.settings_hybrid),
                    stringResource(R.string.settings_hybrid_detail),
                    current.runMode == RunMode.HYBRID,
                    enabled = !savingPreference,
                ) { save { prefs.setRunMode(RunMode.HYBRID) } }
                ModeRow(
                    stringResource(R.string.settings_cloud),
                    stringResource(if (cloudConfigured) R.string.settings_cloud_detail else R.string.settings_cloud_unavailable),
                    current.runMode == RunMode.CLOUD,
                    enabled = cloudConfigured && !savingPreference,
                ) { save { prefs.setRunMode(RunMode.CLOUD) } }
            }
            SettingsTonalButton(stringResource(R.string.settings_models), onOpenModels)
          }

          if (section == "voice") {
            SectionTitle(stringResource(R.string.settings_speed_title))
            // Discrete choices commit once per selection, never once per drag frame.
            Column(Modifier.selectableGroup()) {
                ModeRow(stringResource(R.string.settings_speed_slower), selected = current.speechSpeed < 0.85f, enabled = !savingPreference) {
                    save { prefs.setSpeechSpeed(0.75f) }
                }
                ModeRow(stringResource(R.string.settings_speed_normal), selected = current.speechSpeed in 0.85f..1.15f, enabled = !savingPreference) {
                    save { prefs.setSpeechSpeed(1f) }
                }
                ModeRow(stringResource(R.string.settings_speed_faster), selected = current.speechSpeed > 1.15f, enabled = !savingPreference) {
                    save { prefs.setSpeechSpeed(1.25f) }
                }
            }

          }
          if (section == "listening") {
            SectionTitle(stringResource(R.string.settings_listening))
            ToggleRow(stringResource(R.string.settings_wake_word), current.wakeWordEnabled, enabled = !savingPreference) {
                save { prefs.setWakeWordEnabled(it) }
            }
            NotificationAccessSettings(
                isGranted = { NotificationAccess.granted(context) },
                onManageAccess = { context.startActivity(NotificationAccess.settingsIntent()) },
            )
          }

          if (section == "voice") {
            SectionTitle(stringResource(R.string.settings_voice_title))
            Text(stringResource(R.string.home_cloud_voice), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup()) {
                ModeRow(stringResource(R.string.settings_voice_male), selected = current.voice == VoiceChoice.MALE, enabled = !savingPreference) {
                    save { prefs.setVoice(VoiceChoice.MALE) }
                }
                ModeRow(stringResource(R.string.settings_voice_female), selected = current.voice == VoiceChoice.FEMALE, enabled = !savingPreference) {
                    save { prefs.setVoice(VoiceChoice.FEMALE) }
                }
            }
            SettingsTonalButton(
                label = stringResource(R.string.settings_voice_preview),
                onClick = onPreviewVoice,
                enabled = !savingPreference,
            )
            Text(stringResource(R.string.settings_voice_preview_detail), style = MaterialTheme.typography.bodyMedium)
          }
          if (section == "language") {
            SectionTitle(stringResource(R.string.settings_language))
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    val label = when (language) {
                        AppLanguage.ENGLISH -> R.string.settings_language_english
                        AppLanguage.HINDI -> R.string.settings_language_hindi
                        AppLanguage.HINGLISH -> R.string.settings_language_hinglish
                    }
                    ModeRow(stringResource(label), selected = current.language == language, enabled = !savingPreference) {
                        save { prefs.setLanguage(language) }
                    }
                }
            }
          }

          if (section == "contacts") {
            PhoneAccessPanel()
            SectionTitle(stringResource(R.string.settings_contacts_title))
            ContactSettings(contacts) { saved ->
                prefs.setEmergencyContacts(saved.map { "${it.name}|${it.phone}" }.toSet())
            }
          }

          if (section == "advanced") {
            SectionTitle(stringResource(R.string.settings_cloud_usage_title))
            Text(stringResource(R.string.settings_cloud_usage_detail, current.cloudUsageCount), style = MaterialTheme.typography.bodyMedium)
            // The unavailable explanation already appears beside the Cloud option.
            if (cloudConfigured) {
                Text(
                    if (rateLimitRemaining != null) stringResource(R.string.settings_cloud_remaining, rateLimitRemaining)
                    else stringResource(R.string.settings_cloud_ready),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
            SettingsTonalButton(stringResource(R.string.home_external_tools), onOpenTools)
            SettingsTonalButton(stringResource(R.string.settings_models), onOpenModels)
          }
          }
        }
    }
}

@Composable
private fun SettingsSectionLink(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(icon, null, Modifier.size(26.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 20.dp).semantics { heading() },
    )
}

@Composable
private fun ModeRow(
    title: String,
    subtitle: String = "",
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { if (!selected) onSelect() })
            .padding(vertical = 12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
internal fun NotificationAccessSettings(isGranted: () -> Boolean, onManageAccess: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val checkAccess by rememberUpdatedState(isGranted)
    var granted by remember { mutableStateOf(isGranted()) }
    var openFailed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = checkAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Text(stringResource(if (granted) R.string.settings_notifications_enabled else R.string.settings_notifications_disabled))
    SettingsTonalButton(
        label = stringResource(R.string.settings_notifications_manage),
        onClick = {
            openFailed = false
            try {
                onManageAccess()
            } catch (_: Exception) {
                openFailed = true
            }
        },
    )
    if (openFailed) Text(stringResource(R.string.settings_notifications_error), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun SettingsTonalButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) { Text(label, textAlign = TextAlign.Center) }
}
