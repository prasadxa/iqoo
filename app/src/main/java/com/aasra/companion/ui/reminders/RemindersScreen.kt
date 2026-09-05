package com.aasra.companion.ui.reminders

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aasra.companion.R
import com.aasra.companion.ui.components.PageHeader
import com.aasra.companion.ui.components.NotificationPermission
import com.aasra.data.Reminder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Uses the host's Material theme and localized context. Navigation is owned by the caller. */
@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val factory = remember(app) { viewModelFactory { initializer { RemindersViewModel(app) } } }
    val vm: RemindersViewModel = viewModel(factory = factory)
    val controller = vm.controller
    val state by controller.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Room's existing DAO has no Flow. Refresh on return from settings and
            // while visible so voice-created reminders and daily roll-forwards appear.
            while (isActive) {
                controller.refresh()?.join()
                delay(30_000)
            }
        }
    }
    RemindersContent(state, controller, onBack)
}

@Composable
internal fun RemindersContent(
    state: RemindersUiState,
    controller: RemindersController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val focus = LocalFocusManager.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(state.zone).toString()) }
    var hour by rememberSaveable { mutableStateOf("") }
    var minute by rememberSaveable { mutableStateOf("") }
    var daily by rememberSaveable { mutableStateOf(false) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var choosingTime by rememberSaveable { mutableStateOf(false) }
    var settingsFailed by rememberSaveable { mutableStateOf(false) }
    var lastSavedRevision by rememberSaveable { mutableIntStateOf(state.savedRevision) }
    LaunchedEffect(state.savedRevision) {
        if (state.savedRevision != lastSavedRevision) {
            lastSavedRevision = state.savedRevision
            editing = false
            text = ""
            hour = ""
            minute = ""
            focus.clearFocus()
        }
    }

    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(stringResource(R.string.reminders_title), stringResource(R.string.reminders_back), onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NotificationPermission()
                    if (editing) Text(stringResource(R.string.reminders_zone, state.zone.id), style = MaterialTheme.typography.bodySmall)
                    if (state.loadFailed) TextButton(
                        onClick = { controller.refresh() }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 64.dp),
                    ) { Text(stringResource(if (state.busy) R.string.reminders_working else R.string.reminders_refresh)) }
                    if (state.loadFailed) {
                        ReminderMessage(R.string.reminders_load_failed, error = true)
                    } else if (!state.loaded) {
                        Text(stringResource(R.string.reminders_loading))
                    }
                    if (!editing) state.notice?.let {
                        ReminderMessage(it.stringId(), it != ReminderNotice.SAVED && it != ReminderNotice.CANCELLED)
                    }
                }
            }

            if (state.exactAllowed == false && !editing) item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.reminders_inexact_detail), style = MaterialTheme.typography.bodyMedium)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            TextButton(
                                onClick = {
                                    settingsFailed = false
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                            "package:${context.packageName}".toUri()))
                                    } catch (_: ActivityNotFoundException) {
                                        settingsFailed = true
                                    } catch (_: SecurityException) {
                                        settingsFailed = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            ) { Text(stringResource(R.string.reminders_alarm_settings), textAlign = TextAlign.Center) }
                        }
                        if (settingsFailed) ReminderMessage(R.string.reminders_settings_failed, error = true)
                    }
                }
            }

            item {
                if (editing) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.reminders_add), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() })
                        OutlinedTextField(
                            value = text, onValueChange = { text = it; controller.clearInputError() },
                            label = { Text(stringResource(R.string.reminders_text_label)) },
                            supportingText = { Text(stringResource(R.string.reminders_text_hint)) },
                            isError = state.inputError == ReminderInputError.TEXT,
                            enabled = !state.writing, minLines = 2,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        )
                        val formattedDate = LocalDate.parse(date).format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
                        FilledTonalButton(
                            onClick = { focus.clearFocus(); choosingDate = true },
                            enabled = !state.writing, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        ) { Text(stringResource(R.string.reminders_choose_date, formattedDate), textAlign = TextAlign.Center) }
                        FilledTonalButton(onClick = { focus.clearFocus(); choosingTime = true },
                            enabled = !state.writing, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text(if (hour.isBlank()) stringResource(R.string.reminders_pick_time)
                                else stringResource(R.string.reminders_chosen_time, hour.padStart(2, '0'), minute.padStart(2, '0')))
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .toggleable(value = daily, enabled = !state.writing, role = Role.Checkbox,
                                    onValueChange = { daily = it; controller.clearInputError() })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = daily, onCheckedChange = null, enabled = !state.writing)
                            Text(stringResource(R.string.reminders_repeat_daily), modifier = Modifier.weight(1f).padding(start = 12.dp))
                        }
                        if (daily) Text(stringResource(R.string.reminders_daily_hint), style = MaterialTheme.typography.bodyMedium)
                        state.inputError?.let { ReminderMessage(it.stringId(), error = true) }
                        state.notice?.let {
                            ReminderMessage(it.stringId(), it != ReminderNotice.SAVED && it != ReminderNotice.CANCELLED)
                        }
                        if (state.loadFailed) {
                            ReminderMessage(R.string.reminders_load_failed, error = true)
                            TextButton(onClick = { controller.refresh() }, enabled = !state.busy,
                                modifier = Modifier.heightIn(min = 64.dp)) {
                                Text(stringResource(R.string.reminders_refresh))
                            }
                        }
                    }
                }
            }

            if (!editing) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.reminders_active), style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() })
                    if (state.loaded && state.reminders.isEmpty()) Text(stringResource(R.string.reminders_empty))
                }
            }
            if (!editing) items(state.reminders, key = { it.id }) { reminder ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(reminder.text, style = MaterialTheme.typography.titleLarge)
                    Text(formatReminderTime(reminder.triggerAtMillis, state.zone, locale), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (reminder.repeat == Reminder.REPEAT_DAILY) R.string.reminders_daily else R.string.reminders_once),
                        style = MaterialTheme.typography.bodyMedium)
                    if (reminder.triggerAtMillis < state.checkedAt) {
                        Text(stringResource(R.string.reminders_pending_daily), style = MaterialTheme.typography.bodyMedium)
                    }
                    val cancelDescription = stringResource(R.string.reminders_cancel_description, reminder.text)
                    TextButton(
                        onClick = { controller.requestCancel(reminder) }, enabled = state.loaded && !state.busy && !editing,
                        modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = cancelDescription },
                    ) { Text(stringResource(R.string.reminders_cancel)) }
                }
            }
        }
        Button(onClick = {
            if (editing) controller.save(ReminderDraft(text, date, hour, minute, daily))
            else {
                date = LocalDate.now(state.zone).toString()
                daily = false
                controller.clearInputError()
                editing = true
            }
        }, enabled = state.loaded && !state.busy, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(stringResource(if (editing) R.string.reminders_save else R.string.reminders_add))
        }
        if (editing) TextButton(onClick = { editing = false; controller.clearInputError(); focus.clearFocus() },
            enabled = !state.writing, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(stringResource(R.string.reminders_close_form))
        }
      }
    }

    if (choosingTime) DisposableEffect(context) {
        val dialog = TimePickerDialog(context, { _, h, m ->
            hour = h.toString(); minute = m.toString(); choosingTime = false; controller.clearInputError()
        }, hour.toIntOrNull() ?: 8, minute.toIntOrNull() ?: 0, android.text.format.DateFormat.is24HourFormat(context))
        dialog.setOnDismissListener { choosingTime = false }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
    if (choosingDate) {
        val currentOnDismiss by rememberUpdatedState({ choosingDate = false })
        val currentOnDate by rememberUpdatedState<(LocalDate) -> Unit>({
            date = it.toString(); controller.clearInputError(); choosingDate = false
        })
        DisposableEffect(context) {
            val initial = LocalDate.parse(date)
            val dialog = DatePickerDialog(context, { _, year, month, day ->
                currentOnDate(LocalDate.of(year, month + 1, day))
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            dialog.setOnDismissListener { currentOnDismiss() }
            dialog.show()
            onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
        }
    }

    state.cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { controller.dismissCancel() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.reminders_cancel_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(target.text)
                    Text(formatReminderTime(target.triggerAtMillis, state.zone, locale))
                    Text(stringResource(if (target.repeat == Reminder.REPEAT_DAILY)
                        R.string.reminders_cancel_daily_detail else R.string.reminders_cancel_detail))
                    if (state.notice == ReminderNotice.CANCEL_FAILED) ReminderMessage(R.string.reminders_cancel_failed, error = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.confirmCancel() }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.reminders_cancel_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissCancel() }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.reminders_keep)) }
            },
        )
    }
}

@Composable
private fun ReminderMessage(id: Int, error: Boolean) {
    Text(stringResource(id),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private fun ReminderInputError.stringId(): Int = when (this) {
    ReminderInputError.TEXT -> R.string.reminders_error_text
    ReminderInputError.DATE -> R.string.reminders_error_date
    ReminderInputError.HOUR -> R.string.reminders_error_hour
    ReminderInputError.MINUTE -> R.string.reminders_error_minute
    ReminderInputError.FUTURE -> R.string.reminders_error_future
    ReminderInputError.CLOCK_CHANGE -> R.string.reminders_error_clock_change
    ReminderInputError.ZONE_CHANGED -> R.string.reminders_error_zone_changed
}

private fun ReminderNotice.stringId(): Int = when (this) {
    ReminderNotice.SAVED -> R.string.reminders_saved
    ReminderNotice.SAVE_FAILED -> R.string.reminders_save_failed
    ReminderNotice.SAVED_REFRESH_FAILED -> R.string.reminders_saved_refresh_failed
    ReminderNotice.DUPLICATE -> R.string.reminders_duplicate
    ReminderNotice.CANCELLED -> R.string.reminders_cancelled
    ReminderNotice.CANCEL_FAILED -> R.string.reminders_cancel_failed
    ReminderNotice.CANCELLED_REFRESH_FAILED -> R.string.reminders_cancelled_refresh_failed
}
