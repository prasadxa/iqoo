package com.aasra.companion.ui.reminders

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aasra.data.AasraDatabase
import com.aasra.tools.ReminderTools

internal class RemindersViewModel(private val app: Application) : ViewModel() {
    // The controller invokes these lazy dependencies on IO, including Room initialization.
    private val dao by lazy { AasraDatabase.get(app).reminders() }
    private val tools by lazy { ReminderTools(app, dao) }

    val controller = RemindersController(
        scope = viewModelScope,
        // listReminders() returns a spoken summary, not structured rows or repeat metadata.
        read = { dao.all() },
        create = { text, at, repeat -> tools.setReminder(text, at, repeat) },
        cancel = { id -> tools.cancelReminder(id) },
        exactAllowed = { ReminderTools.exactAlarmGranted(app) },
    )
}
