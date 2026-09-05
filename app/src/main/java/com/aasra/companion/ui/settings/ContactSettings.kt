package com.aasra.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aasra.companion.R
import com.aasra.data.Contact
import com.aasra.data.ContactDao
import com.aasra.data.normalizePhoneNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Room is the source of truth for SOS; the callback maintains the existing preference mirror. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContactSettings(
    contacts: ContactDao,
    onContactsChanged: suspend (List<Contact>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var saved by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<Int?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableLongStateOf(0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Contact?>(null) }

    LaunchedEffect(contacts, loadAttempt) {
        error = null
        try {
            saved = contacts.emergency()
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = R.string.settings_contacts_error
        }
    }

    fun mutate(action: suspend () -> Unit) {
        if (busy || !loaded) return
        // Lock before launching: two taps in the same frame must not enqueue two writes.
        busy = true
        error = null
        scope.launch {
            try {
                action()
                loaded = false
                saved = contacts.emergency()
                loaded = true
                onContactsChanged(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = R.string.settings_contacts_error
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!loaded && error == null) Text(stringResource(R.string.settings_contacts_loading))
        if (loaded && saved.isEmpty()) Text(stringResource(R.string.settings_contacts_empty))
        if (loaded && saved.isNotEmpty() && saved.none { it.isPrimary }) {
            Text(stringResource(R.string.settings_contacts_no_primary))
        }
        saved.forEach { contact ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(contact.name, style = MaterialTheme.typography.titleLarge)
                Text(contact.phone, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (contact.isPrimary) R.string.settings_contact_primary else R.string.settings_contact_backup),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!contact.isPrimary) {
                        val description = stringResource(R.string.settings_contact_primary_description, contact.name)
                        TextButton(
                            onClick = { mutate { check(contacts.setPrimary(contact.id)) } },
                            enabled = loaded && !busy && !editing,
                            modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = description },
                        ) { Text(stringResource(R.string.settings_contact_make_primary)) }
                    }
                    val editDescription = stringResource(R.string.settings_contact_edit_description, contact.name)
                    TextButton(
                        onClick = {
                            editingId = contact.id
                            name = contact.name
                            phone = contact.phone
                            submitted = false
                            error = null
                            editing = true
                        },
                        enabled = loaded && !busy && !editing,
                        modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = editDescription },
                    ) { Text(stringResource(R.string.settings_contact_edit)) }
                    val deleteDescription = stringResource(R.string.settings_contact_delete_description, contact.name)
                    TextButton(
                        onClick = { error = null; deleting = contact },
                        enabled = loaded && !busy && !editing,
                        modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = deleteDescription },
                    ) { Text(stringResource(R.string.settings_contact_delete)) }
                }
            }
        }

        if (editing) {
            Text(
                stringResource(if (editingId == 0L) R.string.settings_contact_add else R.string.settings_contact_edit_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.settings_contact_name)) },
                isError = submitted && name.isBlank(),
                supportingText = if (submitted && name.isBlank()) {
                    { Text(stringResource(R.string.settings_name_required)) }
                } else null,
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it; error = null },
                label = { Text(stringResource(R.string.settings_contact_phone)) },
                isError = submitted && normalizePhoneNumber(phone) == null,
                supportingText = if (submitted && normalizePhoneNumber(phone) == null) {
                    { Text(stringResource(R.string.settings_contact_phone_error)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    submitted = true
                    val normalized = normalizePhoneNumber(phone)
                    if (name.isNotBlank() && normalized != null && !busy) {
                        val enteredName = name.trim()
                        val id = editingId
                        mutate {
                            if (contacts.emergency().any { it.id != id && normalizePhoneNumber(it.phone) == normalized }) {
                                error = R.string.settings_contact_duplicate
                            } else {
                                val existing = if (id == 0L) null else requireNotNull(contacts.byId(id))
                                contacts.saveContact(
                                    existing?.copy(name = enteredName, phone = normalized)
                                        ?: Contact(
                                            name = enteredName,
                                            phone = normalized,
                                            nickname = enteredName.lowercase(),
                                            isEmergency = true,
                                            isPrimary = contacts.emergency().isEmpty(),
                                        ),
                                )
                                // Close after the write, before refreshing, so a refresh failure cannot repeat an insert.
                                editing = false
                                focus.clearFocus()
                            }
                        }
                    }
                },
                enabled = loaded && !busy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text(stringResource(if (busy) R.string.settings_saving else R.string.settings_contact_save), textAlign = TextAlign.Center) }
            TextButton(
                onClick = { editing = false; error = null; focus.clearFocus() },
                enabled = !busy,
                modifier = Modifier.heightIn(min = 64.dp),
            ) { Text(stringResource(R.string.settings_cancel)) }
        } else {
            Button(
                onClick = {
                    editingId = 0L
                    name = ""
                    phone = ""
                    submitted = false
                    error = null
                    editing = true
                },
                enabled = loaded && !busy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text(stringResource(R.string.settings_contact_add), textAlign = TextAlign.Center) }
        }
        error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!loaded) {
                TextButton(onClick = { loadAttempt++ }, modifier = Modifier.heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.settings_retry))
                }
            }
        }
    }

    deleting?.let { contact ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text(stringResource(R.string.settings_contact_delete_title, contact.name)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(when {
                        saved.size == 1 -> R.string.settings_contact_delete_last
                        contact.isPrimary -> R.string.settings_contact_delete_primary
                        else -> R.string.settings_contact_delete_detail
                    }))
                    error?.let {
                        Text(
                            stringResource(it),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { mutate { contacts.deleteById(contact.id); deleting = null } },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) { Text(stringResource(R.string.settings_contact_delete_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = null },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}
