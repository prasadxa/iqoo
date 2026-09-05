package com.aasra.companion.ui.reminders

import com.aasra.data.Reminder
import com.aasra.tools.ToolResult
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ReminderNotice {
    SAVED, SAVE_FAILED, SAVED_REFRESH_FAILED, DUPLICATE,
    CANCELLED, CANCEL_FAILED, CANCELLED_REFRESH_FAILED,
}

internal data class RemindersUiState(
    val reminders: List<Reminder> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val writing: Boolean = false,
    val loadFailed: Boolean = false,
    val exactAllowed: Boolean? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val checkedAt: Long = 0,
    val notice: ReminderNotice? = null,
    val inputError: ReminderInputError? = null,
    val cancelTarget: Reminder? = null,
    val savedRevision: Int = 0,
)

// Serialize UI preflight + insertion across replacement screens as well as rapid taps.
// The existing tools remain the only alarm/Room writers.
private val reminderWriteMutex = Mutex()

/** Call from the UI thread. The owner supplies a lifecycle-bound scope, never a global one. */
internal class RemindersController(
    private val scope: CoroutineScope,
    private val read: suspend () -> List<Reminder>,
    private val create: suspend (String, Long, String) -> ToolResult,
    private val cancel: suspend (Long) -> ToolResult,
    private val exactAllowed: () -> Boolean = { true },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(RemindersUiState())
    val state = mutableState.asStateFlow()

    fun refresh(): Job? = work { reload() }

    fun clearInputError() {
        if (!state.value.writing) mutableState.value = state.value.copy(inputError = null, notice = null)
    }

    fun save(draft: ReminderDraft): Job? {
        if (!state.value.loaded || state.value.busy) return null
        val currentZone = zone()
        if (currentZone != state.value.zone) {
            mutableState.value = state.value.copy(zone = currentZone, inputError = ReminderInputError.ZONE_CHANGED, notice = null)
            return null
        }
        val validation = validateReminder(draft, state.value.zone, now())
        mutableState.value = state.value.copy(inputError = validation.error, notice = null)
        val reminder = validation.reminder ?: return null
        return work(mutating = true) {
            var saved = false
            try {
                reminderWriteMutex.withLock {
                    val duplicate = withContext(ioDispatcher) {
                        read().any {
                            !it.taken && !it.missed && it.text.trim() == reminder.text &&
                                it.triggerAtMillis == reminder.triggerAtMillis && it.repeat == reminder.repeat
                        }
                    }
                    if (duplicate) {
                        mutableState.value = state.value.copy(notice = ReminderNotice.DUPLICATE)
                    } else {
                        val result = withContext(ioDispatcher) {
                            create(reminder.text, reminder.triggerAtMillis, reminder.repeat)
                        }
                        saved = result.ok
                        mutableState.value = state.value.copy(
                            notice = if (saved) ReminderNotice.SAVED else ReminderNotice.SAVE_FAILED,
                            savedRevision = state.value.savedRevision + if (saved) 1 else 0,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = state.value.copy(notice = ReminderNotice.SAVE_FAILED)
            }
            // setReminder inserts before scheduling. Reconcile even on failure; never
            // automatically retry or delete a possibly scheduled record.
            if (!reload() && saved) {
                mutableState.value = state.value.copy(notice = ReminderNotice.SAVED_REFRESH_FAILED)
            }
        }
    }

    fun requestCancel(reminder: Reminder) {
        if (state.value.busy || !state.value.loaded) return
        if (state.value.reminders.none { it.id == reminder.id }) return
        mutableState.value = state.value.copy(cancelTarget = reminder, notice = null)
    }

    fun dismissCancel() {
        if (!state.value.busy) mutableState.value = state.value.copy(cancelTarget = null, notice = null)
    }

    fun confirmCancel(): Job? {
        val target = state.value.cancelTarget ?: return null
        return work(mutating = true) {
            var cancelled = false
            try {
                val result = reminderWriteMutex.withLock {
                    withContext(ioDispatcher) { cancel(target.id) }
                }
                cancelled = result.ok
                mutableState.value = state.value.copy(
                    notice = if (cancelled) ReminderNotice.CANCELLED else ReminderNotice.CANCEL_FAILED,
                    cancelTarget = if (cancelled) null else target,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = state.value.copy(notice = ReminderNotice.CANCEL_FAILED)
            }
            if (!reload() && cancelled) {
                mutableState.value = state.value.copy(notice = ReminderNotice.CANCELLED_REFRESH_FAILED)
            }
        }
    }

    private suspend fun reload(): Boolean = try {
        val checkedAt = now()
        val (rows, exact, currentZone) = withContext(ioDispatcher) {
            Triple(activeReminders(read(), checkedAt), exactAllowed(), zone())
        }
        mutableState.value = state.value.copy(
            reminders = rows, loaded = true, loadFailed = false,
            exactAllowed = exact, zone = currentZone, checkedAt = checkedAt,
            inputError = if (state.value.loaded && state.value.zone != currentZone)
                ReminderInputError.ZONE_CHANGED else state.value.inputError,
        )
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        mutableState.value = state.value.copy(loaded = false, loadFailed = true)
        false
    }

    private fun work(mutating: Boolean = false, action: suspend () -> Unit): Job? {
        if (state.value.busy || !scope.isActive) return null
        // Set synchronously, before launch, so same-frame taps cannot enqueue writes.
        mutableState.value = state.value.copy(busy = true, writing = mutating)
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
            } finally {
                mutableState.value = state.value.copy(busy = false, writing = false)
            }
        }
    }
}
