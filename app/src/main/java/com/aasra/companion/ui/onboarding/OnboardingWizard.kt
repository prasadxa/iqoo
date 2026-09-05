package com.aasra.companion.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.aasra.companion.R
import com.aasra.companion.prefs.AppLanguage
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.prefs.VoiceChoice
import com.aasra.companion.ui.models.ModelDownloadPanel
import com.aasra.companion.ui.components.AasraMark
import com.aasra.data.AasraDatabase
import com.aasra.data.Contact
import com.aasra.data.normalizePhoneNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STEP_LANGUAGE = 0
private const val STEP_VOICE = 1
private const val STEP_NAME = 2
private const val STEP_CONTACTS = 3
private const val STEP_MODELS = 4
private const val STEP_COUNT = 5

internal data class SetupContact(val name: String, val phone: String) {
    val isEmpty: Boolean get() = name.isBlank() && phone.isBlank()
    val normalizedPhone: String? get() = normalizePhoneNumber(phone)
    val isValid: Boolean get() = name.isNotBlank() && normalizedPhone != null
}

@Composable
fun OnboardingWizard(prefs: UserPreferencesRepository, onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(STEP_LANGUAGE) }
    var language by rememberSaveable { mutableStateOf(AppLanguage.HINGLISH) }
    var languageChosen by rememberSaveable { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(VoiceChoice.MALE) }
    var userName by rememberSaveable { mutableStateOf("") }
    var contactNames by rememberSaveable { mutableStateOf(listOf("", "")) }
    var contactPhones by rememberSaveable { mutableStateOf(listOf("", "")) }
    var contactsSkipped by rememberSaveable { mutableStateOf(false) }
    var showContactErrors by rememberSaveable { mutableStateOf(false) }
    var showSkipWarning by rememberSaveable { mutableStateOf(false) }
    var finishing by rememberSaveable { mutableStateOf(false) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var modelsAllDone by remember { mutableStateOf(false) }
    var downloadsStopped by remember { mutableStateOf(false) }
    var backFromModels by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var languageSaveFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    val focus = LocalFocusManager.current
    val contacts = contactNames.indices.map { SetupContact(contactNames[it], contactPhones[it]) }
    val duplicatePhones = contacts.filter { it.isValid }.map { it.normalizedPhone }.let { it.size != it.distinct().size }
    val scrollState = rememberScrollState()

    LaunchedEffect(step) { scrollState.scrollTo(0) }
    LaunchedEffect(language, languageChosen) {
        if (languageChosen) {
            try {
                prefs.setLanguage(language)
                languageSaveFailed = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                languageSaveFailed = true
            }
        }
    }
    LaunchedEffect(finishing, downloadsStopped) {
        if (!finishing || !downloadsStopped || completed) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                val chosen = if (contactsSkipped) emptyList() else contacts.filterNot { it.isEmpty }
                require(chosen.all { it.isValid } && chosen.map { it.normalizedPhone }.distinct().size == chosen.size)
                val db = AasraDatabase.get(context)
                db.withTransaction {
                    val dao = db.contacts()
                    chosen.forEachIndexed { index, draft ->
                        val phone = requireNotNull(draft.normalizedPhone)
                        // A save retried after a preference-write failure or recreation
                        // updates the same contact instead of inserting a duplicate.
                        val existing = dao.all().firstOrNull { normalizePhoneNumber(it.phone) == phone }
                        dao.saveContact(
                            (existing ?: Contact(name = draft.name.trim(), phone = phone)).copy(
                                name = draft.name.trim(), phone = phone,
                                isEmergency = true, isPrimary = index == 0,
                            ),
                        )
                    }
                }
                prefs.setLanguage(language)
                prefs.setVoice(voice)
                prefs.setUserName(userName.trim())
                prefs.setEmergencyContacts(chosen.map { "${it.name.trim()}|${it.normalizedPhone}" }.toSet())
                prefs.setOnboardingDone(true)
            }
            completed = true
            onFinished()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            saveFailed = true
            finishing = false
            downloadsStopped = false
        }
    }

    fun goBack() {
        if (finishing || backFromModels) return
        if (step == STEP_MODELS) backFromModels = true else if (step > 0) step--
    }
    BackHandler(enabled = step > 0 || finishing) { goBack() }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AasraMark(Modifier.size(36.dp))
            Text("Aasra", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.setup_step, step + 1, STEP_COUNT), style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(progress = { (step + 1f) / STEP_COUNT }, modifier = Modifier.fillMaxWidth().height(6.dp))
      Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(when (step) {
                STEP_LANGUAGE -> R.string.setup_language_title
                STEP_VOICE -> R.string.setup_voice_title
                STEP_NAME -> R.string.setup_name_title
                STEP_CONTACTS -> R.string.setup_contacts_title
                else -> R.string.setup_models_title
            }),
            style = MaterialTheme.typography.headlineMedium,
        )
        when (step) {
            STEP_LANGUAGE -> Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    AppLanguage.ENGLISH to R.string.setup_language_english,
                    AppLanguage.HINDI to R.string.setup_language_hindi,
                    AppLanguage.HINGLISH to R.string.setup_language_hinglish,
                ).forEach { (choice, label) ->
                    ChoiceRow(stringResource(label), language == choice) {
                        language = choice
                        languageChosen = true
                    }
                }
                if (languageSaveFailed) Text(stringResource(R.string.setup_language_save_error), color = MaterialTheme.colorScheme.error)
            }
            STEP_VOICE -> Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.home_cloud_voice), style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(stringResource(R.string.setup_voice_male), voice == VoiceChoice.MALE) { voice = VoiceChoice.MALE }
                ChoiceRow(stringResource(R.string.setup_voice_female), voice == VoiceChoice.FEMALE) { voice = VoiceChoice.FEMALE }
            }
            STEP_NAME -> OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text(stringResource(R.string.setup_your_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            )
            STEP_CONTACTS -> {
                Text(stringResource(R.string.setup_contacts_help))
                contacts.forEachIndexed { index, draft ->
                    Text(stringResource(R.string.setup_contact_number, index + 1), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> contactNames = contactNames.mapIndexed { i, old -> if (i == index) value else old } },
                        label = { Text(stringResource(R.string.setup_contact_name, index + 1)) },
                        isError = showContactErrors && !draft.isEmpty && draft.name.isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    )
                    OutlinedTextField(
                        value = draft.phone,
                        onValueChange = { value -> contactPhones = contactPhones.mapIndexed { i, old -> if (i == index) value else old } },
                        label = { Text(stringResource(R.string.setup_contact_phone, index + 1)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        isError = showContactErrors && !draft.isEmpty && draft.normalizedPhone == null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    )
                    if (showContactErrors && !draft.isEmpty && !draft.isValid) {
                        Text(stringResource(R.string.setup_contact_invalid), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (showContactErrors && duplicatePhones) {
                    Text(stringResource(R.string.setup_contact_duplicate), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { showSkipWarning = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.setup_contacts_skip))
                }
            }
            STEP_MODELS -> ModelDownloadPanel(
                onAllDoneChange = { modelsAllDone = it },
                stopRequested = finishing || backFromModels,
                onStopped = {
                    if (backFromModels) {
                        step = STEP_CONTACTS
                        backFromModels = false
                    } else downloadsStopped = true
                },
            )
        }
        if (saveFailed) Text(stringResource(R.string.setup_save_error), color = MaterialTheme.colorScheme.error)
        if (step == STEP_MODELS && !modelsAllDone) Text(stringResource(R.string.setup_models_skip_warning))
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                if (finishing || completed) return@Button
                if (step == STEP_NAME && userName.isBlank()) return@Button
                focus.clearFocus()
                when (step) {
                    STEP_CONTACTS -> {
                        showContactErrors = true
                        when {
                            contacts.all { it.isEmpty } -> showSkipWarning = true
                            contacts.any { !it.isEmpty && !it.isValid } || duplicatePhones -> Unit
                            else -> { contactsSkipped = false; step++ }
                        }
                    }
                    STEP_MODELS -> { saveFailed = false; finishing = true }
                    else -> step++
                }
            },
            enabled = !finishing && !completed && !backFromModels && (step != STEP_NAME || userName.isNotBlank()),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Text(stringResource(when {
                finishing -> R.string.setup_saving
                step != STEP_MODELS -> R.string.setup_next
                modelsAllDone -> R.string.setup_start
                else -> R.string.setup_skip_downloads
            }))
        }
        if (step > 0) {
            TextButton(onClick = ::goBack, enabled = !finishing && !backFromModels, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(R.string.setup_back))
            }
        }
      }
    }
    if (showSkipWarning) {
        AlertDialog(
            onDismissRequest = { showSkipWarning = false },
            title = { Text(stringResource(R.string.setup_contacts_skip_title)) },
            text = { Text(stringResource(R.string.setup_contacts_skip_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    contactsSkipped = true
                    showSkipWarning = false
                    step = STEP_MODELS
                }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.setup_contacts_skip_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSkipWarning = false }, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.setup_contacts_add))
                }
            },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
    }
}
