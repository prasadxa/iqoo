package com.aasra.companion.ui.reminders

import com.aasra.data.Reminder
import com.aasra.tools.ToolResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemindersControllerTest {
    private val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val draft = ReminderDraft("Water", "2026-09-06", "9", "00", true)

    @Test fun loadsOffCallerThreadAndRefreshesPermissionAndRows() = runBlocking {
        val caller = Thread.currentThread()
        var exact = false
        val controller = RemindersController(this, read = {
            assertNotSame(caller, Thread.currentThread())
            listOf(Reminder(id = 1, text = "Water", triggerAtMillis = now + 1))
        }, create = { _, _, _ -> error("No automatic writes") }, cancel = { error("No automatic writes") },
            exactAllowed = { exact }, zone = { zone }, now = { now })
        controller.refresh()!!.join()
        assertTrue(controller.state.value.loaded)
        assertFalse(controller.state.value.exactAllowed!!)
        assertEquals(1, controller.state.value.reminders.size)
        exact = true
        controller.refresh()!!.join()
        assertTrue(controller.state.value.exactAllowed!!)
    }

    @Test fun duplicateTapsAndRetryAfterPartialInsertDoNotCreateAgain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rows = mutableListOf<Reminder>()
        var calls = 0
        val controller = RemindersController(this, read = { rows.toList() }, create = { text, at, repeat ->
            calls++
            rows += Reminder(id = 1, text = text, triggerAtMillis = at, repeat = repeat)
            entered.complete(Unit)
            release.await()
            error("Alarm service failed after Room insert")
        }, cancel = { ToolResult(true, "cancelled") }, zone = { zone }, now = { now })
        controller.refresh()!!.join()
        val save = controller.save(draft)!!
        controller.save(draft)
        entered.await()
        release.complete(Unit)
        save.join()
        assertEquals(ReminderNotice.SAVE_FAILED, controller.state.value.notice)
        assertEquals(0, controller.state.value.savedRevision)
        assertEquals(1, controller.state.value.reminders.size)
        controller.save(draft)!!.join()
        assertEquals(1, calls)
        assertEquals(ReminderNotice.DUPLICATE, controller.state.value.notice)
    }

    @Test fun toolFailureNeverReportsSavedOrClosesDraft() = runBlocking {
        val controller = RemindersController(this, read = { emptyList() },
            create = { _, _, _ -> ToolResult(false, "Rejected") }, cancel = { ToolResult(false, "Rejected") },
            zone = { zone }, now = { now })
        controller.refresh()!!.join()
        controller.save(draft)!!.join()
        assertEquals(ReminderNotice.SAVE_FAILED, controller.state.value.notice)
        assertEquals(0, controller.state.value.savedRevision)
    }

    @Test fun successfulWriteThenFailedReadClosesDraftButDoesNotClaimFreshList() = runBlocking {
        var inserted = false
        val controller = RemindersController(this, read = {
            check(!inserted) { "Database unavailable" }
            emptyList()
        }, create = { text, _, repeat ->
            assertEquals("Water", text)
            assertEquals(Reminder.REPEAT_DAILY, repeat)
            inserted = true
            ToolResult(true, "Saved", "1")
        }, cancel = { ToolResult(true, "Cancelled") }, zone = { zone }, now = { now })
        controller.refresh()!!.join()
        controller.save(draft)!!.join()
        assertEquals(1, controller.state.value.savedRevision)
        assertEquals(ReminderNotice.SAVED_REFRESH_FAILED, controller.state.value.notice)
        assertFalse(controller.state.value.loaded)
        assertNull(controller.save(draft))
    }

    @Test fun cancelNeedsConfirmationAndFailedCancellationKeepsRow() = runBlocking {
        val row = Reminder(id = 4, text = "Walk", triggerAtMillis = now + 60_000)
        var cancelled = false
        var calls = 0
        val controller = RemindersController(this, read = { if (cancelled) emptyList() else listOf(row) },
            create = { _, _, _ -> error("Unused") }, cancel = { id ->
                assertEquals(4L, id)
                calls++
                ToolResult(cancelled, "Result")
            }, zone = { zone }, now = { now })
        controller.refresh()!!.join()
        assertNull(controller.confirmCancel())
        controller.requestCancel(row)
        assertEquals(0, calls)
        controller.dismissCancel()
        assertNull(controller.state.value.cancelTarget)
        controller.requestCancel(row)
        controller.confirmCancel()!!.join()
        assertEquals(ReminderNotice.CANCEL_FAILED, controller.state.value.notice)
        assertEquals(row, controller.state.value.cancelTarget)
        assertEquals(listOf(row), controller.state.value.reminders)
        cancelled = true
        controller.confirmCancel()!!.join()
        assertNull(controller.state.value.cancelTarget)
        assertTrue(controller.state.value.reminders.isEmpty())
        assertEquals(ReminderNotice.CANCELLED, controller.state.value.notice)
    }

    @Test fun cancellationPropagatesWithoutFalseSuccessOrLeakedWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val controller = RemindersController(this, read = { emptyList() }, create = { _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }, cancel = { error("Unused") }, zone = { zone }, now = { now })
        controller.refresh()!!.join()
        val job = controller.save(draft)!!
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(controller.state.value.busy)
        assertEquals(0, controller.state.value.savedRevision)
        assertNotEquals(ReminderNotice.SAVED, controller.state.value.notice)
    }

    @Test fun invalidInputNeverCallsCreate() = runBlocking {
        val controller = RemindersController(this, read = { emptyList() },
            create = { _, _, _ -> error("Invalid form must not write") }, cancel = { error("Unused") },
            zone = { zone }, now = { now })
        controller.refresh()!!.join()
        controller.save(draft.copy(hour = "99"))?.join()
        assertEquals(ReminderInputError.HOUR, controller.state.value.inputError)
    }

    @Test fun cancelledOwnerCannotStartWorkOrLeaveBusyState() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RemindersController(owner, read = { emptyList() },
            create = { _, _, _ -> error("Owner is gone") }, cancel = { error("Unused") })
        controller.refresh()!!.join()
        owner.cancel()
        assertNull(controller.refresh())
        assertFalse(controller.state.value.busy)
    }

    @Test fun changedPhoneZoneRequiresReviewRatherThanSavingInStaleZone() = runBlocking {
        var currentZone = zone
        val controller = RemindersController(this, read = { emptyList() },
            create = { _, _, _ -> error("Review changed zone first") }, cancel = { error("Unused") },
            zone = { currentZone }, now = { now })
        controller.refresh()!!.join()
        currentZone = ZoneId.of("Europe/London")
        controller.save(draft)?.join()
        assertEquals("ZONE_CHANGED", controller.state.value.inputError?.name)
        assertEquals(currentZone, controller.state.value.zone)
        assertEquals(0, controller.state.value.savedRevision)
    }

    @Test fun duplicateCheckFailureDoesNotWriteAndRequiresRefresh() = runBlocking {
        var fail = false
        val controller = RemindersController(this, read = { check(!fail); emptyList() },
            create = { _, _, _ -> error("Do not create without checking duplicates") }, cancel = { error("Unused") },
            zone = { zone }, now = { now })
        controller.refresh()!!.join()
        fail = true
        controller.save(draft)!!.join()
        assertEquals(ReminderNotice.SAVE_FAILED, controller.state.value.notice)
        assertFalse(controller.state.value.loaded)
        assertTrue(controller.state.value.loadFailed)
    }

    @Test fun successfulCreatePassesExactArgumentsOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val rows = mutableListOf<Reminder>()
        val controller = RemindersController(this, read = { rows.toList() },
            create = { text, at, repeat ->
                assertNotSame(caller, Thread.currentThread())
                assertEquals("Water", text)
                assertEquals(Instant.parse("2026-09-06T03:30:00Z").toEpochMilli(), at)
                assertEquals(Reminder.REPEAT_DAILY, repeat)
                rows += Reminder(id = 1, text = text, triggerAtMillis = at, repeat = repeat)
                ToolResult(true, "Saved", "1")
            }, cancel = { error("Unused") }, exactAllowed = { false }, zone = { zone }, now = { now })
        controller.refresh()!!.join()
        controller.save(draft)!!.join()
        assertEquals(ReminderNotice.SAVED, controller.state.value.notice)
        assertEquals(1, controller.state.value.savedRevision)
        assertEquals(rows, controller.state.value.reminders)
        assertFalse(controller.state.value.exactAllowed!!)
    }

    @Test fun separateScreensSerializeDuplicateCheckAndInsert() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rows = mutableListOf<Reminder>()
        var calls = 0
        fun controller() = RemindersController(this, read = { rows.toList() },
            create = { text, at, repeat ->
                calls++
                entered.complete(Unit)
                release.await()
                rows += Reminder(id = 1, text = text, triggerAtMillis = at, repeat = repeat)
                ToolResult(true, "Saved", "1")
            }, cancel = { error("Unused") }, zone = { zone }, now = { now })
        val first = controller()
        val second = controller()
        first.refresh()!!.join()
        second.refresh()!!.join()
        val firstSave = first.save(draft)!!
        entered.await()
        val secondSave = second.save(draft)!!
        release.complete(Unit)
        firstSave.join()
        secondSave.join()
        assertEquals(1, calls)
        assertEquals(ReminderNotice.SAVED, first.state.value.notice)
        assertEquals(ReminderNotice.DUPLICATE, second.state.value.notice)
        assertEquals(1, second.state.value.reminders.size)
    }

    @Test fun backgroundRefreshDoesNotLockDraftFields() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val controller = RemindersController(this, read = { release.await(); emptyList() },
            create = { _, _, _ -> error("Unused") }, cancel = { error("Unused") })
        val job = controller.refresh()!!
        assertTrue(controller.state.value.busy)
        assertFalse(controller.state.value.writing)
        release.complete(Unit)
        job.join()
        assertFalse(controller.state.value.busy)
    }
}
